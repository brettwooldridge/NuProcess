package org.nuprocess.linux;

import com.sun.jna.Native;

/**
 * @author Brett Wooldridge
 */
public class LibEpoll
{
    static
    {
        Native.register("c");
    }

    public static native int epoll_create(int size);

    public static native int epoll_ctl(int epfd, int op, int fd, EpollEvent event);

    public static native int epoll_wait(int epfd, EpollEvent[] events, int maxevents, int timeout);

    int F_GETFL = 3;
    int F_SETFL = 4;

    int O_NONBLOCK = 2048;
}
