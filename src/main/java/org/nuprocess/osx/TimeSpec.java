package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.NativeLong;
import com.sun.jna.Structure;

public final class TimeSpec extends Structure
{
    public NativeLong tv_sec;
    public NativeLong tv_nsec;

    public void setTime(long[] timeval)
    {
        assert timeval.length == 2;
        tv_sec.setValue(timeval[0]);
        tv_nsec.setValue(timeval[1]);
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("tv_sec", "tv_nsec");
    }
}
