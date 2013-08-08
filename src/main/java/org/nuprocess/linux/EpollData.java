package org.nuprocess.linux;

import com.sun.jna.Pointer;
import com.sun.jna.Union;

public class EpollData extends Union
{
    public static class ByValue extends EpollData implements Union.ByValue {};

    public Pointer ptr;
    public int fd;
    public int u32;
    public long u64;
}
