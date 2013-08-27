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
class ProcessKqueue extends BaseEventProcessor<OsxProcess>
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

        Kevent kevent = triggeredEvent;
        int ident = (int) kevent.getIdent();
        int filter = kevent.getFilter();
        int flags = kevent.getFlags();
        int fflags = kevent.getFilterFlags();
        int data = (int) kevent.getData();

        if (filter == Kevent.EVFILT_READ)  // stdout/stderr data available to read
        {
            OsxProcess osxProcess = fildesToProcessMap.get(ident);
            if (osxProcess != null)
            {
                int stdX;
                int available = data;
                if (ident == (stdX = osxProcess.getStdout().get()))
                {
                    osxProcess.readStdout(available);
                    if ((flags & Kevent.EV_EOF) != 0)
                    {
                        osxProcess.readStdout(-1);
                    }
                    else
                    {
                        queueRead(stdX);                            
                    }
                }
                else
                {
                    osxProcess.readStderr(available);
                    if ((flags & Kevent.EV_EOF) != 0)
                    {
                        osxProcess.readStderr(-1);
                    }
                    else
                    {
                        queueRead(osxProcess.getStderr().get());                  
                    }
                }
            }
        }
        else if (filter == Kevent.EVFILT_WRITE) // Room in stdin pipe available to write
        {
            OsxProcess osxProcess = fildesToProcessMap.get(ident);
            if (osxProcess != null)
            {
                int available = data;
                if (available == 0 || osxProcess.writeStdin(available))
                {
                    queueWrite(osxProcess.getStdin().get());
                }
            }
        }
        else if ((fflags & Kevent.NOTE_EXIT) != 0) // process has exited System.gc()
        {
            OsxProcess osxProcess = pidToProcessMap.get(ident);
            if (osxProcess != null)
            {
                cleanupProcess(osxProcess);
                int rc = (data & 0xff00) >> 8;
                osxProcess.onExit(rc);
            }
        }

        kevent.clear();

        return true;
    }

    @Override
    public void registerProcess(OsxProcess process)
    {
        pidToProcessMap.put(process.getPid(), process);
        fildesToProcessMap.put(process.getStdin().get(), process);
        fildesToProcessMap.put(process.getStdout().get(), process);
        fildesToProcessMap.put(process.getStderr().get(), process);

        queueEvent(process.getPid(), Kevent.EVFILT_PROC, Kevent.EV_ADD | Kevent.EV_ONESHOT, Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP);

        queueRead(process.getStdout().get());
        queueRead(process.getStderr().get());
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

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    private void queueRead(int stdX)
    {
        queueEvent(stdX, Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0);
    }

    private void queueEvent(int handle, int filter, int fflags, int data)
    {
        try
        {
            Kevent kevent = null;
            try
            {
                kevent = keventPool.take();
        
                Kevent.EV_SET(kevent, (long) handle, filter, fflags, data, 0l, Pointer.NULL);
                LibKevent.kevent(kqueue, kevent.getPointer(), 1, null, 0, null);
            }            
            finally
            {
                if (kevent != null)
                {
                    keventPool.put(kevent);
                }
            }
        }
        catch (InterruptedException e)
        {
            return;
        }
    }

    private void cleanupProcess(OsxProcess osxProcess)
    {
        int rc = LibC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);
        if (rc == 0)
        {
            System.err.println("Wait on pid retured 0");
        }

        pidToProcessMap.remove(osxProcess.getPid());
        fildesToProcessMap.remove(osxProcess.getStdin());
        fildesToProcessMap.remove(osxProcess.getStdout());
        fildesToProcessMap.remove(osxProcess.getStderr());
    }
}