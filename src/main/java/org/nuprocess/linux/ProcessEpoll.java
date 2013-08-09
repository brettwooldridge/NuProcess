package org.nuprocess.linux;

import org.nuprocess.internal.BaseEventProcessor;

import com.sun.jna.Native;

/**
 * @author Brett Wooldridge
 */
class ProcessEpoll extends BaseEventProcessor<LinuxProcess>
{
    private static final LibC LIBC = LibC.INSTANCE;;
    private static final int WAIT_INDEFINTELY = -1;

    private int epoll;
    private EpollEvent[] eventList;

    ProcessEpoll()
    {
        epoll = LIBC.epoll_create(1024);
        if (epoll < 0)
        {
            throw new RuntimeException("Unable to create kqueue: " + Native.getLastError());
        }

        eventList = (EpollEvent[]) new EpollEvent().toArray(EVENT_BATCH_SIZE);
    }

    @Override
    public void registerProcess(LinuxProcess process)
    {
        pidToProcessMap.put(process.getPid(), process);
        fildesToProcessMap.put(process.getStdin(), process);
        fildesToProcessMap.put(process.getStdout(), process);
        fildesToProcessMap.put(process.getStderr(), process);

        EpollEvent event = new EpollEvent();

        event.events = EpollEvent.EPOLLIN;
        event.fd = process.getStdout();
        int rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, process.getStdout(), event);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to epoll");
        }

        event = new EpollEvent();

        event.events = EpollEvent.EPOLLIN;
        event.fd = process.getStderr();
        rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, process.getStderr(), event);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to epoll");
        }
    }

    @Override
    public void requeueRead(int stdin)
    {
        EpollEvent event = new EpollEvent();
        event.events = EpollEvent.EPOLLOUT | EpollEvent.EPOLLONESHOT | EpollEvent.EPOLLRDHUP;
        event.fd = stdin;
        int rc = LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_DEL, stdin, event);
        rc= LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, stdin, event);
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new events to kqueue");
        }
    }

    @Override
    public void process()
    {        
        int nev = LIBC.epoll_wait(epoll, eventList, EVENT_BATCH_SIZE, WAIT_INDEFINTELY);
        if (nev == -1)
        {
            throw new RuntimeException("Error waiting for epoll");
        }

        for (int i = 0; i < nev; i++)
        {
            EpollEvent epEvent = eventList[i];
            int ident = epEvent.fd; //data.fd;

            if ((epEvent.events & EpollEvent.EPOLLIN) != 0)  // stdout/stderr data available to read
            {
                LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
                if (linuxProcess != null)
                {
                    if (ident == linuxProcess.getStdout())
                    {
                    	linuxProcess.readStdout(false);
                    }
                    else
                    {
                    	linuxProcess.readStderr(false);
                    }
                }
            }
            else if ((epEvent.events & EpollEvent.EPOLLOUT) != 0) // Room in stdin pipe available to write
            {
                LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
                if (linuxProcess != null)
                {
                	if (linuxProcess.writeStdin())
                	{
                	    epEvent.events = EpollEvent.EPOLLOUT | EpollEvent.EPOLLONESHOT;
                	    LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_MOD, ident, epEvent);
                	}
                }
            }

            if ((epEvent.events & EpollEvent.EPOLLHUP) != 0)
            {
                LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
                if (linuxProcess != null)
                {
                    if (ident == linuxProcess.getStdout())
                    {
                        linuxProcess.readStdout(true);
                    }
                    if (ident == linuxProcess.getStderr())
                    {
                        linuxProcess.readStderr(true);
                    }
                }
                LIBC.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_DEL, ident, null);
            }

            epEvent.clear();
        }
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

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

        pidToProcessMap.remove(linuxProcess.getPid());
        fildesToProcessMap.remove(linuxProcess.getStdin());
        fildesToProcessMap.remove(linuxProcess.getStdout());
        fildesToProcessMap.remove(linuxProcess.getStderr());
    }
}