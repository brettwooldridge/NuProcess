package org.nuprocess.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.win32.W32APIOptions;

public interface NuKernel32 extends Kernel32
{
    /** The instance. */
    NuKernel32 INSTANCE = (NuKernel32) Native.loadLibrary("kernel32",
                                                          NuKernel32.class, W32APIOptions.UNICODE_OPTIONS);


    HANDLE CreateNamedPipe(String name, int dwOpenMode, int dwPipeMode, int nMaxInstances,
    		int nOutBufferSize, int nInBufferSize, int nDefaultTimeOut, SECURITY_ATTRIBUTES securityAttributes);

    int PIPE_ACCESS_DUPLEX = 0x00000003;
    int PIPE_ACCESS_INBOUND = 0x00000002;
    int PIPE_ACCESS_OUTBOUND = 0x00000001;

    int FILE_FLAG_OVERLAPPED = 0x40000000;
}
