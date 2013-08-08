package org.nuprocess.linux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Brett Wooldridge
 */
class ProcessEpoll implements Runnable
{
    private static final LibC LIBC;
    private static final int EVENT_BATCH_SIZE;
    private static final int WAIT_INDEFINTELY = -1;

    private volatile CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    private Map<Integer, LinuxProcess> pidToProcessMap;
    private Map<Integer, LinuxProcess> fildesToProcessMap;
    private int epoll;

    static
    {
    	LIBC = LibC.INSTANCE;

    	EVENT_BATCH_SIZE = Integer.getInteger("org.nuprocess.eventBatchSize", 8); 
    }
    
    ProcessEpoll()
    {
    	pidToProcessMap = new ConcurrentHashMap<Integer, LinuxProcess>();
        fildesToProcessMap = new ConcurrentHashMap<Integer, LinuxProcess>();
        isRunning = new AtomicBoolean();
        startBarrier = new CyclicBarrier(2);

        epoll = LIBC.epoll_create(0);
        if (epoll < 0)
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

            EpollEvent[] eventList = (EpollEvent[]) new EpollEvent().toArray(EVENT_BATCH_SIZE);
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

    void queueChangeList(LinuxProcess linuxProcess)
    {
        pidToProcessMap.put(linuxProcess.pid, linuxProcess);
        fildesToProcessMap.put(linuxProcess.stdin, linuxProcess);
        fildesToProcessMap.put(linuxProcess.stdout, linuxProcess);
        fildesToProcessMap.put(linuxProcess.stderr, linuxProcess);

//        Kevent.EV_SET(events[0], new NativeLong(linuxProcess.pid),
//                      Kevent.EVFILT_PROC,
//                      Kevent.EV_ADD,
//                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
//                      new NativeLong(0), Pointer.NULL);
//
        EpollEvent event = new EpollEvent();

        event.events = EpollEvent.EPOLLIN;
        event.data.fd = linuxProcess.stdout;
        int rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, linuxProcess.stdout, event);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to epoll");
        }

        event.events = EpollEvent.EPOLLIN;
        event.data.fd = linuxProcess.stderr;
        rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, linuxProcess.stderr, event);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to kqueue");
        }

        event.events = EpollEvent.EPOLLOUT | EpollEvent.EPOLLONESHOT;
        event.data.fd = linuxProcess.stdin;
        rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, linuxProcess.stdin, event);
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

    private void process(EpollEvent[] eventList) throws InterruptedException
    {
        int nev = LIBC.epoll_wait(epoll, eventList, EVENT_BATCH_SIZE, WAIT_INDEFINTELY);
        if (nev == -1)
        {
            throw new RuntimeException("Error waiting for epoll");
        }

        for (int i = 0; i < nev; i++)
        {
            EpollEvent epEvent = eventList[i];
            int ident = epEvent.data.fd;

            if (epEvent.events == EpollEvent.EPOLLIN)  // stdout/stderr data available to read
            {
                LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
                if (linuxProcess != null)
                {
                    if (ident == linuxProcess.stdout)
                    {
                    	linuxProcess.readStdout();
//                        if ((epEvent.flags & Kevent.EV_EOF) != 0)
//                        {
//                        	linuxProcess.readStdout(-1);
//                        }
                    }
                    else
                    {
                    	linuxProcess.readStderr();
//                        if ((epEvent.flags & Kevent.EV_EOF) != 0)
//                        {
//                        	linuxProcess.readStderr(-1);
//                        }
                    }
                }
            }
            else if (epEvent.events == EpollEvent.EPOLLOUT) // Room in stdin pipe available to write
            {
                LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
                if (linuxProcess != null)
                {
                    // int available = epEvent.data != null ? epEvent.data.intValue() : -1;
                	if (linuxProcess.writeStdin())
                	{
                		requeueRead(linuxProcess.stdin);
                	}
                }
            }
//            else if ((epEvent.fflags & Kevent.NOTE_EXIT) != 0) // process has exited
//            {
//                OsxProcess osxProcess = pidToProcessMap.get(ident);
//                if (osxProcess != null)
//                {
//                    osxProcess.onExit(epEvent.data.intValue());
//                }
//
//                cleanupProcess(osxProcess);                
//            }
        }
    }

    private void requeueRead(int stdin)
    {
        EpollEvent event = new EpollEvent();
        event.events = EpollEvent.EPOLLOUT | EpollEvent.EPOLLONESHOT;
        event.data.fd = stdin;
        int rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, stdin, event);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to kqueue");
        }
    }

    private void cleanupProcess(LinuxProcess linuxProcess)
    {
//        Kevent[] events = (Kevent[]) new Kevent().toArray(1);
//
//        Kevent.EV_SET(events[0], new NativeLong(linuxProcess.pid), 
//                      Kevent.EVFILT_PROC,
//                      Kevent.EV_DELETE | Kevent.EV_ENABLE | Kevent.EV_ONESHOT,
//                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
//                      new NativeLong(0), Pointer.NULL);
//
//        LIBC.kevent(epoll, events, 1, null, 0, Pointer.NULL);

        pidToProcessMap.remove(linuxProcess.pid);
        fildesToProcessMap.remove(linuxProcess.stdin);
        fildesToProcessMap.remove(linuxProcess.stdout);
        fildesToProcessMap.remove(linuxProcess.stderr);
    }
}