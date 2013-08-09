package org.nuprocess.osx;

import org.nuprocess.internal.BaseEventProcessor;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * @author Brett Wooldridge
 */
class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
    private static final LibC LIBC = LibC.INSTANCE;

    private int kqueue;
    private Kevent[] eventList;

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
    public void requeueRead(int stdin)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(stdin),
                Kevent.EVFILT_WRITE,
                Kevent.EV_ADD | Kevent.EV_ONESHOT,
                0,
                new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, Pointer.NULL);
    }

    @Override
    public void registerProcess(OsxProcess process)
    {
        pidToProcessMap.put(process.getPid(), process);
        fildesToProcessMap.put(process.getStdin(), process);
        fildesToProcessMap.put(process.getStdout(), process);
        fildesToProcessMap.put(process.getStderr(), process);

        Kevent[] events = (Kevent[]) new Kevent().toArray(3);

        Kevent.EV_SET(events[0], new NativeLong(process.getPid()),
                      Kevent.EVFILT_PROC,
                      Kevent.EV_ADD,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[1], new NativeLong(process.getStdout()),
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD,
                      0,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[2], new NativeLong(process.getStderr()),
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD,
                      0,
                      new NativeLong(0), Pointer.NULL);

        int rc = LIBC.kevent(kqueue, events, events.length, null, 0, Pointer.NULL);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to kqueue");
        }
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    public void process()
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
                    }
                    else
                    {
                    	osxProcess.readStderr(available);
                        if ((kevent.flags & Kevent.EV_EOF) != 0)
                        {
                        	osxProcess.readStderr(-1);
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
                		requeueRead(osxProcess.getStdin());
                	}
                }
            }
            else if ((kevent.fflags & Kevent.NOTE_EXIT) != 0) // process has exited
            {
                OsxProcess osxProcess = pidToProcessMap.get(ident);
                if (osxProcess != null)
                {
                    osxProcess.onExit(kevent.data.intValue());
                }

                cleanupProcess(osxProcess);                
            }

            kevent.clear();
        }
    }

    private void cleanupProcess(OsxProcess osxProcess)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(osxProcess.getPid()), 
                      Kevent.EVFILT_PROC,
                      Kevent.EV_DELETE | Kevent.EV_ENABLE | Kevent.EV_ONESHOT,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, Pointer.NULL);

        pidToProcessMap.remove(osxProcess.getPid());
        fildesToProcessMap.remove(osxProcess.getStdin());
        fildesToProcessMap.remove(osxProcess.getStdout());
        fildesToProcessMap.remove(osxProcess.getStderr());
    }
}