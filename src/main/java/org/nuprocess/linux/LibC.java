package org.nuprocess.linux;

import java.nio.Buffer;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
public interface LibC extends Library
{
    LibC INSTANCE = (LibC) Native.loadLibrary("c", LibC.class);

    int pipe(int[] fildes);

    int fcntl(int fildes, int cmd, Pointer args);

    int close(int fildes);

    int write(int fildes, Buffer buf, int nbyte);

    int read(int fildes, Buffer buf, int nbyte);

    int kill(int pid, int sig);

    int wait(IntByReference status);

    int epoll_create(int size);

    int epoll_ctl(int epfd, int op, int fd, EpollEvent event);

    int epoll_wait(int epfd, EpollEvent[] events, int maxevents, int timeout);
}
