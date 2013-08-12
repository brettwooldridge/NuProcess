package org.nuprocess.windows;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.win32.W32APIOptions;

public interface NuKernel32 extends Kernel32
{
    /** The instance. */
    NuKernel32 INSTANCE = (NuKernel32) Native.loadLibrary("kernel32",
                                                          NuKernel32.class, W32APIOptions.UNICODE_OPTIONS);

    Pointer GetEnvironmentStringsW();

    void FreeEnvironmentStringsW(Pointer p);
}
