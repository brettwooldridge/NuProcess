package org.nuprocess.linux;

import org.nuprocess.internal.ILibC;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * @author Brett Wooldridge
 */
public interface LibC extends Library, ILibC
{
    LibC INSTANCE = (LibC) Native.loadLibrary("c", LibC.class);


    int epoll_create(int size);

    int epoll_ctl(int epfd, int op, int fd, EpollEvent event);

    int epoll_wait(int epfd, EpollEvent[] events, int maxevents, int timeout);

    int F_GETFL = 3;
    int F_SETFL = 4;

    int O_NONBLOCK = 2048;
}
