package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * @author Brett Wooldridge
 */
public class LibKevent
{
    static
    {
        Native.register("c");
    }

    public static native int kqueue();

    public static native int kevent(int kq, Pointer changeList, int nchanges, Pointer eventList, int nevents, TimeSpec timespec);

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
