/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nuprocess.osx;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.nuprocess.internal.BaseEventProcessor;
import org.nuprocess.internal.LibC;
import org.nuprocess.osx.LibKevent.TimeSpec;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
final class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
    private static final int KEVENT_POOL_SIZE = 16;
    private static final TimeSpec timeSpec;

    private int kqueue;
    private Kevent triggeredEvent;
    private BlockingQueue<Kevent> keventPool;

    static
    {
        timeSpec = new TimeSpec();
        timeSpec.tv_sec = 0;
        timeSpec.tv_nsec = TimeUnit.MILLISECONDS.toNanos(DEADPOOL_POLL_INTERVAL);
    }

    ProcessKqueue()
    {
        kqueue = LibKevent.kqueue();
        if (kqueue < 0)
        {
            throw new RuntimeException("Unable to create kqueue");
        }

        triggeredEvent = new Kevent();

        keventPool = new ArrayBlockingQueue<Kevent>(KEVENT_POOL_SIZE);
        for (int i = 0; i < KEVENT_POOL_SIZE; i++)
        {
            keventPool.add(new Kevent());
        }
    }

    // ************************************************************************
    //                         IEventProcessor methods
    // ************************************************************************

    @Override
    public void registerProcess(OsxProcess process)
    {
        pidToProcessMap.put(process.getPid(), process);
        fildesToProcessMap.put(process.getStdin().get(), process);
        fildesToProcessMap.put(process.getStdout().get(), process);
        fildesToProcessMap.put(process.getStderr().get(), process);

        queueEvent(process.getPid(), Kevent.EVFILT_PROC, Kevent.EV_ADD | Kevent.EV_ONESHOT, Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP);

        queueEvent(process.getStdout().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0);
        queueEvent(process.getStderr().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0);
    }

    @Override
    public void queueWrite(int stdin)
    {
        queueEvent(stdin, Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0);
    }

    @Override
    public void closeStdin(int stdin)
    {
        fildesToProcessMap.remove(stdin);
    }

    @Override
    public boolean process()
    {
        int nev = LibKevent.kevent(kqueue, null, 0, triggeredEvent.getPointer(), 1, timeSpec); //Pointer.NULL);
        if (nev == -1)
        {
            throw new RuntimeException("Error waiting for kevent");
        }

        if (nev == 0)
        {
            return false;
        }

        final Kevent kevent = triggeredEvent;
        int ident = (int) kevent.getIdent();
        int filter = kevent.getFilter();
        int data = (int) kevent.getData();

        OsxProcess osxProcess = fildesToProcessMap.get(ident);
        if (osxProcess == null)
        {
            osxProcess = pidToProcessMap.get(ident);
            if (osxProcess == null)
            {
                return true;
            }
        }

        if (filter == Kevent.EVFILT_READ) // stdout/stderr data available to read
        {
            int available = data;
            if (ident == osxProcess.getStdout().get())
            {
                osxProcess.readStdout(available);
                if ((kevent.getFlags() & Kevent.EV_EOF) != 0)
                {
                    osxProcess.readStdout(-1);
                }
                else
                {
                    queueEvent(ident, Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0);
                }
            }
            else
            {
                osxProcess.readStderr(available);
                if ((kevent.getFlags() & Kevent.EV_EOF) != 0)
                {
                    osxProcess.readStderr(-1);
                }
                else
                {
                    queueEvent(osxProcess.getStderr().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0);
                }
            }
        }
        else if (filter == Kevent.EVFILT_WRITE) // Room in stdin pipe available to write
        {
            int available = data;
            if (available == 0 || osxProcess.writeStdin(available))
            {
                queueWrite(osxProcess.getStdin().get());
            }
        }
        else if ((kevent.getFilterFlags() & Kevent.NOTE_EXIT) != 0) // process has exited System.gc()
        {
            cleanupProcess(osxProcess);
            int rc = (data & 0xff00) >> 8;
            osxProcess.onExit(rc);
        }

        kevent.clear();

        return true;
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    private void queueEvent(int handle, int filter, int fflags, int data)
    {
        try
        {
            Kevent kevent = keventPool.take();

            Kevent.EV_SET(kevent, (long) handle, filter, fflags, data, 0l, Pointer.NULL);
            LibKevent.kevent(kqueue, kevent.getPointer(), 1, null, 0, null);

            keventPool.put(kevent);
        }
        catch (InterruptedException e)
        {
            return;
        }
    }

    private void cleanupProcess(OsxProcess osxProcess)
    {
        LibC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);

        pidToProcessMap.remove(osxProcess.getPid());
        fildesToProcessMap.remove(osxProcess.getStdin());
        fildesToProcessMap.remove(osxProcess.getStdout());
        fildesToProcessMap.remove(osxProcess.getStderr());
    }
}