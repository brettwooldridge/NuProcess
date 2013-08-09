package org.nuprocess.linux;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;

public class EpollEvent extends Structure
{
    public int events;
    public int fd;
    public int unused;

    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("events", "fd", "unused");
    }

    /* from /usr/include/sys/epoll.h */
    public static final int EPOLL_CTL_ADD = 1; /* Add a file decriptor to the interface.  */
    public static final int EPOLL_CTL_DEL = 2; /* Change file decriptor epoll_event structure.  */
    public static final int EPOLL_CTL_MOD = 3; /* Remove a file decriptor from the interface.  */

    public static final int EPOLLIN      = 0x001;
    public static final int EPOLLOUT     = 0x004;
    public static final int EPOLLERR     = 0x008;
    public static final int EPOLLHUP     = 0x010;
    public static final int EPOLLRDHUP   = 0x2000;
    public static final int EPOLLONESHOT = (1 << 30);
    public static final int EPOLLET      = (1 << 31);
}
