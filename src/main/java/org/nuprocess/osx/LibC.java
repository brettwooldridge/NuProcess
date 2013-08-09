package org.nuprocess.osx;

import org.nuprocess.internal.ILibC;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * @author Brett Wooldridge
 */
public interface LibC extends Library, ILibC
{
    LibC INSTANCE = (LibC) Native.loadLibrary("c", LibC.class);

    PThread.ByValue pthread_self();

    int kqueue();

    int kevent(int kq, Kevent[] changeList, int nchanges, Kevent[] eventList, int nevents, Pointer timespec);

    int O_NONBLOCK = 0x0004;

}
