package org.nuprocess.windows;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.win32.W32APIOptions;

public class NuWinBase32
{
    static
    {
        NativeLibrary nativeLibrary = NativeLibrary.getInstance("winbase", W32APIOptions.UNICODE_OPTIONS);
        Native.register(nativeLibrary);
    }

    public static native boolean HasOverlappedIoCompleted(NuKernel32.OVERLAPPED lpOverlapped);
}
