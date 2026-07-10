/*
 * Copyright (C) 2015 Ben Hamilton
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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Constants and struct accessors for the Windows APIs used by NuProcess.
 * All structs use the 64-bit (x64 / ARM64) layouts; FFM is not available
 * on 32-bit Windows JVMs.
 */
public final class NuWinNT
{
   public static final int CREATE_SUSPENDED = 0x00000004;
   public static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
   public static final int CREATE_NO_WINDOW = 0x08000000;

   public static final int ERROR_SUCCESS = 0;
   public static final int ERROR_BROKEN_PIPE = 109;
   public static final int ERROR_PIPE_NOT_CONNECTED = 233;
   public static final int ERROR_PIPE_CONNECTED = 535;
   public static final int ERROR_IO_PENDING = 997;

   public static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;
   public static final int FILE_FLAG_OVERLAPPED = 0x40000000;
   public static final int FILE_SHARE_READ = 0x00000001;
   public static final int FILE_SHARE_WRITE = 0x00000002;

   public static final int GENERIC_READ = 0x80000000;
   public static final int GENERIC_WRITE = 0x40000000;

   public static final int OPEN_EXISTING = 3;

   public static final int STATUS_PENDING = 0x00000103;
   public static final int STILL_ACTIVE = STATUS_PENDING;

   public static final int STARTF_USESTDHANDLES = 0x100;

   /** A {@code HANDLE} is simply a native address; this is {@code (HANDLE) -1}. */
   public static final MemorySegment INVALID_HANDLE_VALUE = MemorySegment.ofAddress(-1L);

   private NuWinNT() {
   }

   public static boolean isInvalidHandle(MemorySegment handle)
   {
      return handle == null || handle.address() == INVALID_HANDLE_VALUE.address();
   }

   public static boolean isNullHandle(MemorySegment handle)
   {
      return handle == null || handle.address() == 0L;
   }

   /**
    * Accessors for a {@code SECURITY_ATTRIBUTES} struct laid out in native memory.
    */
   public static final class SECURITY_ATTRIBUTES
   {
      public static final long SIZE = 24;
      private static final long NLENGTH_OFFSET = 0;
      @SuppressWarnings("unused")
      private static final long LP_SECURITY_DESCRIPTOR_OFFSET = 8;
      private static final long BINHERIT_HANDLE_OFFSET = 16;

      private SECURITY_ATTRIBUTES() {
      }

      public static MemorySegment allocate(Arena arena, boolean inheritHandle)
      {
         MemorySegment segment = arena.allocate(SIZE, 8);
         segment.set(JAVA_INT, NLENGTH_OFFSET, (int) SIZE);
         segment.set(JAVA_INT, BINHERIT_HANDLE_OFFSET, inheritHandle ? 1 : 0);
         return segment;
      }
   }

   /**
    * Accessors for a {@code STARTUPINFOW} struct laid out in native memory.
    */
   public static final class STARTUPINFO
   {
      public static final long SIZE = 104;
      private static final long CB_OFFSET = 0;
      private static final long DWFLAGS_OFFSET = 60;
      private static final long HSTDINPUT_OFFSET = 80;
      private static final long HSTDOUTPUT_OFFSET = 88;
      private static final long HSTDERROR_OFFSET = 96;

      private STARTUPINFO() {
      }

      public static MemorySegment allocate(Arena arena)
      {
         MemorySegment segment = arena.allocate(SIZE, 8);
         segment.set(JAVA_INT, CB_OFFSET, (int) SIZE);
         return segment;
      }

      public static void setFlags(MemorySegment startupInfo, int flags)
      {
         startupInfo.set(JAVA_INT, DWFLAGS_OFFSET, flags);
      }

      public static void setStdHandles(MemorySegment startupInfo, MemorySegment hStdInput, MemorySegment hStdOutput, MemorySegment hStdError)
      {
         startupInfo.set(ADDRESS, HSTDINPUT_OFFSET, hStdInput);
         startupInfo.set(ADDRESS, HSTDOUTPUT_OFFSET, hStdOutput);
         startupInfo.set(ADDRESS, HSTDERROR_OFFSET, hStdError);
      }
   }

   /**
    * Accessors for a {@code PROCESS_INFORMATION} struct laid out in native memory.
    */
   public static final class PROCESS_INFORMATION
   {
      public static final long SIZE = 24;
      private static final long HPROCESS_OFFSET = 0;
      private static final long HTHREAD_OFFSET = 8;
      @SuppressWarnings("unused")
      private static final long DWPROCESS_ID_OFFSET = 16;
      @SuppressWarnings("unused")
      private static final long DWTHREAD_ID_OFFSET = 20;

      private PROCESS_INFORMATION() {
      }

      public static MemorySegment allocate(Arena arena)
      {
         return arena.allocate(SIZE, 8);
      }

      public static MemorySegment getProcess(MemorySegment processInformation)
      {
         return processInformation.get(ADDRESS, HPROCESS_OFFSET);
      }

      public static MemorySegment getThread(MemorySegment processInformation)
      {
         return processInformation.get(ADDRESS, HTHREAD_OFFSET);
      }
   }

   /**
    * Accessors for an {@code OVERLAPPED} struct laid out in native memory. NuProcess only
    * ever passes zeroed OVERLAPPED structs and never examines their contents in Java, so
    * only allocation is supported.
    */
   public static final class OVERLAPPED
   {
      public static final long SIZE = 32;

      private OVERLAPPED() {
      }

      public static MemorySegment allocate(Arena arena)
      {
         return arena.allocate(SIZE, 8);
      }
   }
}
