package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import org.nuprocess.internal.ILibC;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * @author Brett Wooldridge
 */
public interface LibC extends Library, ILibC
{
    LibC INSTANCE = (LibC) Native.loadLibrary("c", LibC.class);

    int kqueue();

    int kevent(int kq, Pointer changeList, int nchanges, Pointer eventList, int nevents, TimeSpec timespec);

    int O_NONBLOCK = 0x0004;

    public static class TimeSpec extends Structure
    {
        public long tv_sec;
        public long tv_nsec;

        @SuppressWarnings("rawtypes")
        @Override
        protected List getFieldOrder()
        {
            return Arrays.asList("tv_sec", "tv_nsec");
        }   
    }
}
