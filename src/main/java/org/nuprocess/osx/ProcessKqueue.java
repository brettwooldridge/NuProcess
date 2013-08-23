package org.nuprocess.osx;

import java.util.concurrent.TimeUnit;

import org.nuprocess.internal.BaseEventProcessor;
import org.nuprocess.osx.LibC.TimeSpec;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
    private static final LibC LIBC = LibC.INSTANCE;
    private static final TimeSpec timeSpec;

    private int kqueue;
    private Kevent[] eventList;

    static
    {
        timeSpec = new TimeSpec();
        timeSpec.tv_sec = 0;
        timeSpec.tv_nsec = TimeUnit.MILLISECONDS.toNanos(DEADPOOL_POLL_INTERVAL);
    }

    ProcessKqueue()
    {
        kqueue = LIBC.kqueue();
        if (kqueue < 0)
        {
            throw new RuntimeException("Unable to create kqueue");
        }

        eventList = (Kevent[]) new Kevent().toArray(EVENT_BATCH_SIZE);
    }

    // ************************************************************************
    //                         IEventProcessor methods
    // ************************************************************************

    @Override
    public boolean process()
    {
        int nev = LIBC.kevent(kqueue, null, 0, eventList, eventList.length, timeSpec); //Pointer.NULL);
        if (nev == -1)
        {
            throw new RuntimeException("Error waiting for kevent");
        }

        if (nev == 0)
        {
            return false;
        }

        for (int i = 0; i < nev; i++)
        {
            Kevent kevent = eventList[i];
            int ident = kevent.ident.intValue();

            if (kevent.filter == Kevent.EVFILT_READ)  // stdout/stderr data available to read
            {
                OsxProcess osxProcess = fildesToProcessMap.get(ident);
                if (osxProcess != null)
                {
                    int available = kevent.data != null ? kevent.data.intValue() : -1;
                    if (ident == osxProcess.getStdout())
                    {
                        osxProcess.readStdout(available);
                        if ((kevent.flags & Kevent.EV_EOF) != 0)
                        {
                            osxProcess.readStdout(-1);
                        }
                        else
                        {
                            queueRead(osxProcess.getStdout());                            
                        }
                    }
                    else
                    {
                        osxProcess.readStderr(available);
                        if ((kevent.flags & Kevent.EV_EOF) != 0)
                        {
                            osxProcess.readStderr(-1);
                        }
                        else
                        {
                            queueRead(osxProcess.getStderr());                            
                        }
                    }
                }
            }
            else if (kevent.filter == Kevent.EVFILT_WRITE) // Room in stdin pipe available to write
            {
                OsxProcess osxProcess = fildesToProcessMap.get(ident);
                if (osxProcess != null)
                {
                    int available = kevent.data != null ? kevent.data.intValue() : -1;
                    if (osxProcess.writeStdin(available))
                    {
                        queueWrite(osxProcess.getStdin());
                    }
                }
            }
            else if ((kevent.fflags & Kevent.NOTE_EXIT) != 0) // process has exited System.gc()
            {
                OsxProcess osxProcess = pidToProcessMap.get(ident);
                if (osxProcess != null)
                {
                    cleanupProcess(osxProcess);
                    int rc = (kevent.data.intValue() & 0xff00) >> 8;
                    osxProcess.onExit(rc);
                }
            }

            kevent.clear();
        }

        return true;
    }

    @Override
    public void registerProcess(OsxProcess process)
    {
        pidToProcessMap.put(process.getPid(), process);
        fildesToProcessMap.put(process.getStdin(), process);
        fildesToProcessMap.put(process.getStdout(), process);
        fildesToProcessMap.put(process.getStderr(), process);

        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(process.getPid()),
                      Kevent.EVFILT_PROC,
                      Kevent.EV_ADD | Kevent.EV_ONESHOT,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        int rc = LIBC.kevent(kqueue, events, 1, null, 0, null);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to kqueue");
        }

        queueRead(process.getStdout());
        queueRead(process.getStderr());
    }

    @Override
    public void queueWrite(int stdin)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(stdin),
                Kevent.EVFILT_WRITE,
                Kevent.EV_ADD | Kevent.EV_ONESHOT,
                0,
                new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, null);
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    private void queueRead(int stdX)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(stdX),
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD | Kevent.EV_ONESHOT,
                      0,
                      new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, null);
    }

    private void cleanupProcess(OsxProcess osxProcess)
    {
        LIBC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);

        pidToProcessMap.remove(osxProcess.getPid());
        fildesToProcessMap.remove(osxProcess.getStdin());
        fildesToProcessMap.remove(osxProcess.getStdout());
        fildesToProcessMap.remove(osxProcess.getStderr());
    }
}