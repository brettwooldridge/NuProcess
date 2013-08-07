package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.NativeLong;

public final class DefaultNativeTimeval extends NativeTimeval
{
    public NativeLong tv_sec;
    public NativeLong tv_usec;

    public void setTime(long[] timeval)
    {
        assert timeval.length == 2;
        tv_sec.setValue(timeval[0]);
        tv_usec.setValue(timeval[1]);
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("tv_sec", "tv_usec");
    }
}
