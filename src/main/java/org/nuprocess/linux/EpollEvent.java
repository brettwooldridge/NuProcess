package org.nuprocess.linux;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Structure;

public class EpollEvent extends Structure
{
    public static class ByValue extends EpollEvent implements Structure.ByValue {}

    public int events;
    public EpollData data;

    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("events", "data");
    }

    /* from /usr/include/sys/epoll.h */
    public static final int EPOLL_CTL_ADD = 1; /* Add a file decriptor to the interface.  */
    public static final int EPOLL_CTL_MOD = 2; /* Remove a file decriptor from the interface.  */
    public static final int EPOLL_CTL_DEL = 3; /* Change file decriptor epoll_event structure.  */

    public static final int EPOLLIN      = 0x001;
    public static final int EPOLLOUT     = 0x004;
    public static final int EPOLLONESHOT = (1 << 30);
    public static final int EPOLLET      = (1 << 31);
}
