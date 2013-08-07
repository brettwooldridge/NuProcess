package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

public class PThread extends Structure
{
    public static class ByValue extends PThread implements Structure.ByValue { };

    // long __sig;
    public NativeLong __sig;
    // struct __darwin_pthread_handler_rec *__cleanup_stack
    public Pointer __pdarwin_pthread_handler_rec;
    // char __opaque[__PTHREAD_SIZE__];
    public Pointer __opaque;
    
    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("__sig", "__pdarwin_pthread_handler_rec", "__opaque");
    }
}
