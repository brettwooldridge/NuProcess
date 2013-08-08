package org.nuprocess.osx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * @author Brett Wooldridge
 */
class ProcessKqueue implements Runnable
{
    private static final LibC LIBC;
    private static final int EVENT_BATCH_SIZE;

    private volatile CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    private Map<Integer, OsxProcess> pidToProcessMap;
    private Map<Integer, OsxProcess> fildesToProcessMap;
    private int kqueue;

    static
    {
    	LIBC = LibC.INSTANCE;

    	EVENT_BATCH_SIZE = Integer.getInteger("org.nuprocess.eventBatchSize", 8); 
    }
    
    ProcessKqueue()
    {
    	pidToProcessMap = new ConcurrentHashMap<Integer, OsxProcess>();
        fildesToProcessMap = new ConcurrentHashMap<Integer, OsxProcess>();
        isRunning = new AtomicBoolean();
        startBarrier = new CyclicBarrier(2);

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
            startBarrier.await();

            Kevent[] eventList = (Kevent[]) new Kevent().toArray(EVENT_BATCH_SIZE);
            do
            {
                process(eventList);
                
                // Clear the structures for the next loop - necessary?
                for (int i = 0; i < EVENT_BATCH_SIZE; i++)
                {
                    eventList[i].clear();
                }
            } while (!isRunning.compareAndSet(pidToProcessMap.isEmpty(), false));
	    }
        catch (Exception e)
        {
            // TODO: how to handle this error?
	    	isRunning.set(false);
        }
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    void queueChangeList(OsxProcess osxProcess)
    {
        pidToProcessMap.put(osxProcess.pid, osxProcess);
        fildesToProcessMap.put(osxProcess.stdin, osxProcess);
        fildesToProcessMap.put(osxProcess.stdout, osxProcess);
        fildesToProcessMap.put(osxProcess.stderr, osxProcess);

        Kevent[] events = (Kevent[]) new Kevent().toArray(4);

        Kevent.EV_SET(events[0], new NativeLong(osxProcess.pid),
                      Kevent.EVFILT_PROC,
                      Kevent.EV_ADD,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[1], new NativeLong(osxProcess.stdout),
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD,
                      0,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[2], new NativeLong(osxProcess.stderr),
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD,
                      0,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[3], new NativeLong(osxProcess.stdin),
                      Kevent.EVFILT_WRITE,
                      Kevent.EV_ADD | Kevent.EV_ONESHOT,
                      0,
                      new NativeLong(0), Pointer.NULL);

        int rc = LIBC.kevent(kqueue, events, events.length, null, 0, Pointer.NULL);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to kqueue");
        }
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
        this.startBarrier = processorRunning;
    }

    /**
     * @return
     */
    boolean checkAndSetRunning()
    {
        return isRunning.compareAndSet(false, true);
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

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

            if (kevent.filter == Kevent.EVFILT_READ)  // stdout/stderr data available to read
            {
                OsxProcess osxProcess = fildesToProcessMap.get(ident);
                if (osxProcess != null)
                {
                    int available = kevent.data != null ? kevent.data.intValue() : -1;
                    if (ident == osxProcess.stdout)
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
                		requeueRead(osxProcess.stdin);
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
        }
    }

    private void requeueRead(int stdin)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(stdin),
                Kevent.EVFILT_WRITE,
                Kevent.EV_ADD | Kevent.EV_ONESHOT,
                0,
                new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, Pointer.NULL);
    }

    private void cleanupProcess(OsxProcess osxProcess)
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(1);

        Kevent.EV_SET(events[0], new NativeLong(osxProcess.pid), 
                      Kevent.EVFILT_PROC,
                      Kevent.EV_DELETE | Kevent.EV_ENABLE | Kevent.EV_ONESHOT,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        LIBC.kevent(kqueue, events, 1, null, 0, Pointer.NULL);

        pidToProcessMap.remove(osxProcess.pid);
        fildesToProcessMap.remove(osxProcess.stdin);
        fildesToProcessMap.remove(osxProcess.stdout);
        fildesToProcessMap.remove(osxProcess.stderr);
    }
}