package org.nuprocess.linux;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.internal.BaseEventProcessor;
import org.nuprocess.internal.LibC;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
class ProcessEpoll extends BaseEventProcessor<LinuxProcess>
{
    private int epoll;
    private EpollEvent triggeredEvent;
    private List<LinuxProcess> deadPool;

    ProcessEpoll()
    {
        epoll = LibEpoll.epoll_create(1024);
        if (epoll < 0)
        {
            throw new RuntimeException("Unable to create kqueue: " + Native.getLastError());
        }

        triggeredEvent = new EpollEvent();
        deadPool = new LinkedList<LinuxProcess>();
    }

    // ************************************************************************
    //                         IEventProcessor methods
    // ************************************************************************

    @Override
    public void registerProcess(LinuxProcess process)
    {
        pidToProcessMap.put(process.getPid(), process);
        fildesToProcessMap.put(process.getStdin().get(), process);
        fildesToProcessMap.put(process.getStdout().get(), process);
        fildesToProcessMap.put(process.getStderr().get(), process);

        EpollEvent event = new EpollEvent();

        event.setEvents(EpollEvent.EPOLLIN);
        event.setFd(process.getStdout().get());
        int rc = LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, process.getStdout().get(), event.getPointer());
        if (rc == -1)
        {
            rc = Native.getLastError();
            event.free();
            throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
        }
        event.free();

        event = new EpollEvent();

        event.setEvents(EpollEvent.EPOLLIN);
        event.setFd(process.getStderr().get());
        rc = LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, process.getStderr().get(), event.getPointer());
        if (rc == -1)
        {
            rc = Native.getLastError();
            event.free();
            throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
        }
        event.free();
    }

    @Override
    public void queueWrite(int stdin)
    {
        EpollEvent event = new EpollEvent();
        event.setEvents(EpollEvent.EPOLLOUT | EpollEvent.EPOLLONESHOT | EpollEvent.EPOLLRDHUP | EpollEvent.EPOLLHUP);
        event.setFd(stdin);
        int rc = LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_MOD, stdin, event.getPointer());
        if (rc == -1)
        {
           rc = LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_DEL, stdin, event.getPointer());
           rc = LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_ADD, stdin, event.getPointer());
        }
        event.free();
        if (rc == -1)
        {
            throw new RuntimeException("Unable to register new event to epoll queue");
        }
    }

    @Override
    public void closeStdin(int stdin)
    {
        fildesToProcessMap.remove(stdin);
        LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_DEL, stdin, null);
    }

    @Override
    public boolean process()
    {
        try
        {
            int nev = LibEpoll.epoll_wait(epoll, triggeredEvent.getPointer(), 1, DEADPOOL_POLL_INTERVAL);
            if (nev == -1)
            {
                throw new RuntimeException("Error waiting for epoll");
            }
    
            if (nev == 0)
            {
                return false;
            }
    
            EpollEvent epEvent = triggeredEvent;
            int ident = epEvent.getFd();
            int events = epEvent.getEvents();

            LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
            if (linuxProcess == null)
            {
                return true;
            }
    
            if ((events & EpollEvent.EPOLLIN) != 0)  // stdout/stderr data available to read
            {
                if (ident == linuxProcess.getStdout().get())
                {
                	linuxProcess.readStdout(NuProcess.BUFFER_CAPACITY);
                }
                else
                {
                	linuxProcess.readStderr(NuProcess.BUFFER_CAPACITY);
                }
            }
            else if ((events & EpollEvent.EPOLLOUT) != 0) // Room in stdin pipe available to write
            {
            	if (linuxProcess.getStdin().get() != -1)
            	{
            	    if (linuxProcess.writeStdin(NuProcess.BUFFER_CAPACITY))
            	    {
            	        epEvent.setEvents(EpollEvent.EPOLLOUT | EpollEvent.EPOLLONESHOT | EpollEvent.EPOLLRDHUP | EpollEvent.EPOLLHUP);
            	        LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_MOD, ident, epEvent.getPointer());
            	    }
            	}
            }
    
            if ((events & EpollEvent.EPOLLHUP) != 0 || (events & EpollEvent.EPOLLRDHUP) != 0 || (events & EpollEvent.EPOLLERR) != 0)
            {
                LibEpoll.epoll_ctl(epoll, EpollEvent.EPOLL_CTL_DEL, ident, null);
                if (ident == linuxProcess.getStdout().get())
                {
                    linuxProcess.readStdout(-1);
                }
                else if (ident == linuxProcess.getStderr().get())
                {
                    linuxProcess.readStderr(-1);
                }
                else if (ident == linuxProcess.getStdin().get())
                {
                    linuxProcess.stdinClose();
                }
            }
    
            if (linuxProcess.isSoftExit())
            {
                cleanupProcess(linuxProcess);
            }
            
            return true;
        }
        finally
        {
            triggeredEvent.clear();
            checkDeadPool();
        }
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    private void cleanupProcess(LinuxProcess linuxProcess)
    {
        pidToProcessMap.remove(linuxProcess.getPid());
        fildesToProcessMap.remove(linuxProcess.getStdin().get());
        fildesToProcessMap.remove(linuxProcess.getStdout().get());
        fildesToProcessMap.remove(linuxProcess.getStderr().get());

        linuxProcess.close(linuxProcess.getStdin());
        linuxProcess.close(linuxProcess.getStdout());
        linuxProcess.close(linuxProcess.getStderr());

        IntByReference exitCode = new IntByReference();
        int rc = LibC.waitpid(linuxProcess.getPid(), exitCode, LibC.WNOHANG);
        if (rc != 0)
        {
            if (rc == -1)
            {
                rc = Native.getLastError();
                linuxProcess.onExit(Integer.MAX_VALUE);
                return;
            }

            rc = (exitCode.getValue() & 0xff00) >> 8;
            if (rc == 127)
            {
                linuxProcess.onExit(Integer.MIN_VALUE);
                return;
            }

            linuxProcess.onExit(rc);
        }
        else
        {
            deadPool.add(linuxProcess);
        }
    }

    private void checkDeadPool()
    {
        if (deadPool.isEmpty())
        {
            return;
        }

        IntByReference exitCode = new IntByReference();
        Iterator<LinuxProcess> iterator = deadPool.iterator();
        while (iterator.hasNext())
        {
            LinuxProcess process = iterator.next();
            int rc = LibC.waitpid(process.getPid(), exitCode, LibC.WNOHANG);
            if (rc == 0)
            {
                System.err.println("Still waiting for " + process.getPid());
                continue;
            }

            iterator.remove();
            if (rc == -1)
            {
                rc = Native.getLastError();
                process.onExit(Integer.MAX_VALUE);
                continue;
            }

            rc = (exitCode.getValue() & 0xff00) >> 8;
            if (rc == 127)
            {
                process.onExit(Integer.MIN_VALUE);
                continue;
            }

            process.onExit(rc);
        }
    }
}