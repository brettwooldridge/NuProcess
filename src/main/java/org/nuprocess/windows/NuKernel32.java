package org.nuprocess.windows;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

public interface NuKernel32 extends Kernel32
{
    /** The instance. */
    NuKernel32 INSTANCE = (NuKernel32) Native.loadLibrary("kernel32",
                                                          NuKernel32.class, W32APIOptions.UNICODE_OPTIONS);

    HANDLE CreateIoCompletionPort(HANDLE fileHandle, HANDLE existingCompletionPort, ULONG_PTR completionKey, int numberOfThreads);

    boolean GetQueuedCompletionStatus(HANDLE completionPort, IntByReference numberOfBytes, ULONG_PTRByReference completionKey, OVERLAPPED.ByReference lpOverlapped, int dwMilliseconds);

    HANDLE CreateNamedPipe(String name, int dwOpenMode, int dwPipeMode, int nMaxInstances,
    		int nOutBufferSize, int nInBufferSize, int nDefaultTimeOut, SECURITY_ATTRIBUTES securityAttributes);

    boolean ConnectNamedPipe(HANDLE hNamedPipe, OVERLAPPED lpo);

    DWORD ResumeThread(HANDLE hThread);

    public static class OVERLAPPED extends Structure {
        public static class ByReference extends OVERLAPPED implements Structure.ByReference {
            
        }

        public ULONG_PTR Internal;
        public ULONG_PTR InternalHigh;
        public int Offset;
        public int OffsetHigh;
        public HANDLE hEvent;
        
        public OVERLAPPED()
        {
            super();
        }

        public OVERLAPPED(Pointer p)
        {
            super(p);
        }

        @SuppressWarnings("rawtypes")
        protected List getFieldOrder() {
            return Arrays.asList(new String[] { "Internal", "InternalHigh", "Offset", "OffsetHigh", "hEvent" });
        }
    }        

    int PIPE_ACCESS_DUPLEX = 0x00000003;
    int PIPE_ACCESS_INBOUND = 0x00000002;
    int PIPE_ACCESS_OUTBOUND = 0x00000001;

    int FILE_FLAG_OVERLAPPED = 0x40000000;
}
