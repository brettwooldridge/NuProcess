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

package com.zaxxer.nuprocess.windows;

import com.zaxxer.nuprocess.internal.FfmSupport;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings for the kernel32 functions used by NuProcess. All {@code HANDLE}s are
 * represented as (zero-length) {@link MemorySegment}s. All calls capture
 * {@code GetLastError}, which can be retrieved with {@link #getLastError()}.
 */
public class NuKernel32
{
   private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

   private static final MethodHandle GET_PROCESS_ID =
      FfmSupport.downcall(KERNEL32, "GetProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS));
   private static final MethodHandle CLOSE_HANDLE =
      FfmSupport.downcall(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
   private static final MethodHandle CREATE_IO_COMPLETION_PORT =
      FfmSupport.downcall(KERNEL32, "CreateIoCompletionPort", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
   private static final MethodHandle CREATE_PROCESS_W =
      FfmSupport.downcall(KERNEL32, "CreateProcessW",
                          FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
   private static final MethodHandle TERMINATE_PROCESS =
      FfmSupport.downcall(KERNEL32, "TerminateProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
   private static final MethodHandle CREATE_FILE_W =
      FfmSupport.downcall(KERNEL32, "CreateFileW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
   private static final MethodHandle GET_QUEUED_COMPLETION_STATUS =
      FfmSupport.downcall(KERNEL32, "GetQueuedCompletionStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
   private static final MethodHandle POST_QUEUED_COMPLETION_STATUS =
      FfmSupport.downcall(KERNEL32, "PostQueuedCompletionStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
   private static final MethodHandle CREATE_NAMED_PIPE_W =
      FfmSupport.downcall(KERNEL32, "CreateNamedPipeW",
                          FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
   private static final MethodHandle CONNECT_NAMED_PIPE =
      FfmSupport.downcall(KERNEL32, "ConnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
   private static final MethodHandle RESUME_THREAD =
      FfmSupport.downcall(KERNEL32, "ResumeThread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
   private static final MethodHandle GET_EXIT_CODE_PROCESS =
      FfmSupport.downcall(KERNEL32, "GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
   private static final MethodHandle READ_FILE =
      FfmSupport.downcall(KERNEL32, "ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
   private static final MethodHandle WRITE_FILE =
      FfmSupport.downcall(KERNEL32, "WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

   public static int getLastError()
   {
      return FfmSupport.getLastError();
   }

   public static int GetProcessId(MemorySegment hObject)
   {
      try {
         return (int) GET_PROCESS_ID.invokeExact(FfmSupport.captureState(), hObject);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static boolean CloseHandle(MemorySegment hObject)
   {
      try {
         return ((int) CLOSE_HANDLE.invokeExact(FfmSupport.captureState(), hObject)) != 0;
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static MemorySegment CreateIoCompletionPort(MemorySegment fileHandle, MemorySegment existingCompletionPort, long completionKey, int numberOfThreads)
   {
      try {
         return (MemorySegment) CREATE_IO_COMPLETION_PORT.invokeExact(FfmSupport.captureState(), fileHandle,
                                                                      existingCompletionPort == null ? MemorySegment.NULL : existingCompletionPort,
                                                                      completionKey, numberOfThreads);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static boolean CreateProcessW(MemorySegment lpApplicationName, MemorySegment lpCommandLine, MemorySegment lpProcessAttributes,
                                        MemorySegment lpThreadAttributes, boolean bInheritHandles, int dwCreationFlags, MemorySegment lpEnvironment,
                                        MemorySegment lpCurrentDirectory, MemorySegment lpStartupInfo, MemorySegment lpProcessInformation)
   {
      try {
         return ((int) CREATE_PROCESS_W.invokeExact(FfmSupport.captureState(), lpApplicationName, lpCommandLine, lpProcessAttributes, lpThreadAttributes,
                                                    bInheritHandles ? 1 : 0, dwCreationFlags, lpEnvironment, lpCurrentDirectory,
                                                    lpStartupInfo, lpProcessInformation)) != 0;
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static boolean TerminateProcess(MemorySegment hProcess, int exitCode)
   {
      try {
         return ((int) TERMINATE_PROCESS.invokeExact(FfmSupport.captureState(), hProcess, exitCode)) != 0;
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static MemorySegment CreateFile(MemorySegment lpFileName, int dwDesiredAccess, int dwShareMode, MemorySegment lpSecurityAttributes,
                                          int dwCreationDisposition, int dwFlagsAndAttributes, MemorySegment hTemplateFile)
   {
      try {
         return (MemorySegment) CREATE_FILE_W.invokeExact(FfmSupport.captureState(), lpFileName, dwDesiredAccess, dwShareMode, lpSecurityAttributes,
                                                          dwCreationDisposition, dwFlagsAndAttributes, hTemplateFile);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static int GetQueuedCompletionStatus(MemorySegment completionPort, MemorySegment numberOfBytes, MemorySegment completionKey,
                                               MemorySegment lpOverlapped, int dwMilliseconds)
   {
      try {
         return (int) GET_QUEUED_COMPLETION_STATUS.invokeExact(FfmSupport.captureState(), completionPort, numberOfBytes, completionKey,
                                                               lpOverlapped, dwMilliseconds);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static boolean PostQueuedCompletionStatus(MemorySegment completionPort, int dwNumberOfBytesTransferred, long dwCompletionKey,
                                                    MemorySegment lpOverlapped)
   {
      try {
         return ((int) POST_QUEUED_COMPLETION_STATUS.invokeExact(FfmSupport.captureState(), completionPort, dwNumberOfBytesTransferred, dwCompletionKey,
                                                                 lpOverlapped)) != 0;
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static MemorySegment CreateNamedPipeW(MemorySegment name, int dwOpenMode, int dwPipeMode, int nMaxInstances, int nOutBufferSize,
                                                int nInBufferSize, int nDefaultTimeOut, MemorySegment securityAttributes)
   {
      try {
         return (MemorySegment) CREATE_NAMED_PIPE_W.invokeExact(FfmSupport.captureState(), name, dwOpenMode, dwPipeMode, nMaxInstances, nOutBufferSize,
                                                                nInBufferSize, nDefaultTimeOut, securityAttributes);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static int ConnectNamedPipe(MemorySegment hNamedPipe, MemorySegment lpo)
   {
      try {
         return (int) CONNECT_NAMED_PIPE.invokeExact(FfmSupport.captureState(), hNamedPipe, lpo == null ? MemorySegment.NULL : lpo);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static int ResumeThread(MemorySegment hThread)
   {
      try {
         return (int) RESUME_THREAD.invokeExact(FfmSupport.captureState(), hThread);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   /**
    * On a successful call the process exit code is stored in {@code exitCode[0]}.
    */
   public static boolean GetExitCodeProcess(MemorySegment hProcess, int[] exitCode)
   {
      try {
         MemorySegment exitCodeSegment = FfmSupport.scratch();
         boolean rc = ((int) GET_EXIT_CODE_PROCESS.invokeExact(FfmSupport.captureState(), hProcess, exitCodeSegment)) != 0;
         exitCode[0] = exitCodeSegment.get(JAVA_INT, 0);
         return rc;
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   /**
    * Reads into {@code lpBuffer} starting at the buffer's current position. The buffer's
    * position is <em>not</em> updated.
    */
   public static int ReadFile(MemorySegment hFile, ByteBuffer lpBuffer, int nNumberOfBytesToRead, MemorySegment lpNumberOfBytesRead,
                              MemorySegment lpOverlapped)
   {
      try {
         MemorySegment buffer = MemorySegment.ofBuffer(lpBuffer);
         return (int) READ_FILE.invokeExact(FfmSupport.captureState(), hFile, buffer, nNumberOfBytesToRead,
                                            lpNumberOfBytesRead == null ? MemorySegment.NULL : lpNumberOfBytesRead, lpOverlapped);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   /**
    * Writes from {@code lpBuffer} starting at the buffer's current position. The buffer's
    * position is <em>not</em> updated.
    */
   public static int WriteFile(MemorySegment hFile, ByteBuffer lpBuffer, int nNumberOfBytesToWrite, MemorySegment lpNumberOfBytesWritten,
                               MemorySegment lpOverlapped)
   {
      try {
         MemorySegment buffer = MemorySegment.ofBuffer(lpBuffer);
         return (int) WRITE_FILE.invokeExact(FfmSupport.captureState(), hFile, buffer, nNumberOfBytesToWrite,
                                             lpNumberOfBytesWritten == null ? MemorySegment.NULL : lpNumberOfBytesWritten, lpOverlapped);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static final int PIPE_ACCESS_DUPLEX = 0x00000003;
   public static final int PIPE_ACCESS_INBOUND = 0x00000002;
   public static final int PIPE_ACCESS_OUTBOUND = 0x00000001;

   public static final int FILE_FLAG_OVERLAPPED = 0x40000000;
}
