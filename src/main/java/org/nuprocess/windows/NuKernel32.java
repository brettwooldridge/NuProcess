/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nuprocess.windows;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;

public class NuKernel32
{
    static
    {
        NativeLibrary nativeLibrary = NativeLibrary.getInstance("kernel32", W32APIOptions.UNICODE_OPTIONS);
        Native.register(NuKernel32.class, nativeLibrary);
    }
    
    public static native boolean CloseHandle(HANDLE hObject);

    public static native HANDLE CreateIoCompletionPort(HANDLE fileHandle, HANDLE existingCompletionPort, ULONG_PTR completionKey, int numberOfThreads);

    public static native boolean CreateProcessW(WString lpApplicationName, char[] lpCommandLine,
                                                WinBase.SECURITY_ATTRIBUTES lpProcessAttributes,
                                                WinBase.SECURITY_ATTRIBUTES lpThreadAttributes,
                                                boolean bInheritHandles, DWORD dwCreationFlags,
                                                Pointer lpEnvironment, String lpCurrentDirectory,
                                                WinBase.STARTUPINFO lpStartupInfo,
                                                WinBase.PROCESS_INFORMATION lpProcessInformation);

    public static native boolean TerminateProcess(HANDLE hProcess, int exitCode);

    public static native HANDLE CreateFile(WString lpFileName, int dwDesiredAccess, int dwShareMode,
                                           WinBase.SECURITY_ATTRIBUTES lpSecurityAttributes,
                                           int dwCreationDisposition, int dwFlagsAndAttributes,
                                           HANDLE hTemplateFile);

    public static native HANDLE CreateEvent(WinBase.SECURITY_ATTRIBUTES lpEventAttributes,
                                            boolean bManualReset, boolean bInitialState, String lpName);

    public static native int WaitForSingleObject(HANDLE hHandle, int dwMilliseconds);

    public static native int GetQueuedCompletionStatus(HANDLE completionPort, IntByReference numberOfBytes, 
                                                           ULONG_PTRByReference completionKey, PointerByReference lpOverlapped,
                                                           int dwMilliseconds);

    public static native boolean PostQueuedCompletionStatus(HANDLE completionPort, int dwNumberOfBytesTransferred,
                                                            ULONG_PTR dwCompletionKey, OVERLAPPED lpOverlapped);

    public static native HANDLE CreateNamedPipeW(WString name, int dwOpenMode, int dwPipeMode, int nMaxInstances,
                                                 int nOutBufferSize, int nInBufferSize, int nDefaultTimeOut,
                                                 SECURITY_ATTRIBUTES securityAttributes);

    public static native int ConnectNamedPipe(HANDLE hNamedPipe, OVERLAPPED lpo);

    public static native boolean DisconnectNamedPipe(HANDLE hNamedPipe);

    public static native DWORD ResumeThread(HANDLE hThread);

    public static native boolean GetExitCodeProcess(HANDLE hProcess, IntByReference exitCode);

    public static native int ReadFile(HANDLE hFile, Pointer lpBuffer, int nNumberOfBytesToRead,
                                          IntByReference lpNumberOfBytesRead, NuKernel32.OVERLAPPED lpOverlapped);

    public static native int WriteFile(HANDLE hFile, Pointer lpBuffer, int nNumberOfBytesToWrite,
                                           IntByReference lpNumberOfBytesWritten, NuKernel32.OVERLAPPED lpOverlapped);

    /**
     * The OVERLAPPED structure contains information used in 
     * asynchronous (or overlapped) input and output (I/O).
     */
    public static class OVERLAPPED extends Structure
    {
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

    public static final int PIPE_ACCESS_DUPLEX = 0x00000003;
    public static final int PIPE_ACCESS_INBOUND = 0x00000002;
    public static final int PIPE_ACCESS_OUTBOUND = 0x00000001;

    public static final int FILE_FLAG_OVERLAPPED = 0x40000000;
}
