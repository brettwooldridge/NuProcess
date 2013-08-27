package org.nuprocess.linux;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * @author Brett Wooldridge
 */
public class LibEpoll
{
    static
    {
        Native.register("c");
    }

    public static native int sigignore(int signal);

    public static native int epoll_create(int size);

    public static native int epoll_ctl(int epfd, int op, int fd, Pointer event);

    public static native int epoll_wait(int epfd, Pointer events, int maxevents, int timeout);

    public static final int SIGPIPE = 13;
}
