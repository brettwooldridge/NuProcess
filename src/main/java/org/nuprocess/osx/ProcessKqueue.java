package org.nuprocess.osx;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

class ProcessKqueue implements Runnable
{
    private static final LibC LIBC;

    static
    {
        LIBC = LibC.INSTANCE;
    }

    private volatile CyclicBarrier processorRunning;
    private AtomicBoolean isRunning;

    private Map<Integer, OsxProcess> pidToProcessMap;
    private Map<Integer, OsxProcess> stdinToProcessMap;
    private Map<Integer, OsxProcess> stdoutToProcessMap;
    private Map<Integer, OsxProcess> stderrToProcessMap;
    private int wakeupPipe;
    private int kqueue;

    ProcessKqueue(Map<Integer, OsxProcess> pidToProcessMap,
                  Map<Integer, OsxProcess> stdinToProcessMap,
                  Map<Integer, OsxProcess> stdoutToProcessMap,
                  Map<Integer, OsxProcess> stderrToProcessMap)
    {
        this.pidToProcessMap = pidToProcessMap;
        this.stdinToProcessMap = stdinToProcessMap;
        this.stdoutToProcessMap = stdoutToProcessMap;
        this.stderrToProcessMap = stderrToProcessMap;
        this.isRunning = new AtomicBoolean();
        this.processorRunning = new CyclicBarrier(2);

        kqueue = LIBC.kqueue();
        if (kqueue < 0)
        {
            throw new RuntimeException("Unable to create kqueue");
        }
    }

    /**
     * The primary run loop of the kqueue event processor.
     */
    public void run()
    {
        try
        {
            processorRunning.await();

            Kevent[] eventList = (Kevent[]) new Kevent().toArray(1);
            do
            {
                try
                {
                    process(eventList);
                    
                    // Clear the structures for the next loop
                    for (int i = 0; i < eventList.length; i++)
                    {
                        eventList[i].clear();
                    }
                }
                catch (InterruptedException ie)
                {
                    break;
                }
            } while (!isRunning.compareAndSet(pidToProcessMap.isEmpty(), false));
        }
        catch (Exception e)
        {
            // TODO: how to handle this error?
            return;
        }
    }

    int getKqueue()
    {
        return kqueue;
    }

    /**
     * Set the CyclicBarrier that this thread should join, along with the OsxProcess
     * thread that is starting this processor.  Used to cause the OsxProcess to wait
     * until the processor is up and running before returning from start() to the
     * user.
     *
     * @param processorRunning a CyclicBarrier to join
     */
    void setBarrier(CyclicBarrier processorRunning)
    {
        this.processorRunning = processorRunning;
    }

    /**
     * @return
     */
    boolean checkAndSetRunning()
    {
        return isRunning.compareAndSet(false, true);
    }

    private void process(Kevent[] eventList) throws InterruptedException
    {
        int nev = LIBC.kevent(kqueue, null, 0, eventList, eventList.length, Pointer.NULL);
        if (nev == -1)
        {
            throw new RuntimeException("Error waiting for kevent");
        }

        for (int i = 0; i < nev; i++)
        {
            Kevent kevent = eventList[i];
            int ident = kevent.ident.intValue();

            if (ident == wakeupPipe)
            {
                int available = kevent.data.intValue();
                LIBC.read(wakeupPipe, ByteBuffer.allocateDirect(available), available);
                continue;
            }

            if ((kevent.fflags & Kevent.NOTE_EXIT) != 0)
            {
                unlistenKevent(ident);

                OsxProcess osxProcess = pidToProcessMap.get(ident);
                if (osxProcess != null)
                {
                    osxProcess.onExit(kevent.data.intValue());
                }
                continue;
            }

            OsxProcess osxProcess = stdoutToProcessMap.get(ident);
            if (osxProcess != null)
            {
                int available = kevent.data != null ? kevent.data.intValue() : -1;
                osxProcess.readStdout(available);

                if ((kevent.flags & Kevent.EV_EOF) != 0)
                {
                	osxProcess.readStdout(-1);
                }
                continue;
            }

            osxProcess = stderrToProcessMap.get(ident);
            if (osxProcess != null)
            {
                int available = kevent.data != null ? kevent.data.intValue() : -1;
                osxProcess.readStderr(available);

                if ((kevent.flags & Kevent.EV_EOF) != 0)
                {
                	osxProcess.readStderr(-1);
                }
                continue;
            }

            osxProcess = stdinToProcessMap.get(ident);
            if (osxProcess != null)
            {
                continue;
            }
        }
    }

    private void unlistenKevent(int pid)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(pid), 
                      Kevent.EVFILT_PROC,
                      Kevent.EV_DELETE | Kevent.EV_ENABLE | Kevent.EV_ONESHOT,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, Pointer.NULL);
    }
}