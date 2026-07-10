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

package com.zaxxer.nuprocess.osx;

import com.zaxxer.nuprocess.internal.FfmSupport;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * FFM bindings for kqueue/kevent on macOS (and FreeBSD).
 *
 * @author Brett Wooldridge
 */
public class LibKevent
{
   private static final SymbolLookup LIBC = FfmSupport.LINKER.defaultLookup();

   private static final MethodHandle KQUEUE =
      FfmSupport.downcall(LIBC, "kqueue", FunctionDescriptor.of(JAVA_INT));
   private static final MethodHandle KEVENT =
      FfmSupport.downcall(LIBC, "kevent", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

   public static int kqueue()
   {
      try {
         return (int) KQUEUE.invokeExact(FfmSupport.captureState());
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   /**
    * @param changeList the address of an array of {@link Kevent} structs, or {@link MemorySegment#NULL}
    * @param eventList the address of an array of {@link Kevent} structs, or {@link MemorySegment#NULL}
    * @param timespec a {@link TimeSpec} struct, or {@link MemorySegment#NULL} to block indefinitely
    */
   public static int kevent(int kq, MemorySegment changeList, int nchanges, MemorySegment eventList, int nevents, MemorySegment timespec)
   {
      try {
         return (int) KEVENT.invokeExact(FfmSupport.captureState(), kq, changeList, nchanges, eventList, nevents, timespec);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static int getLastError()
   {
      return FfmSupport.getLastError();
   }

   /**
    * Accessors for a {@code struct timespec} laid out in native memory.
    */
   public static final class TimeSpec
   {
      public static final long SIZE = 16;

      private TimeSpec() {
      }

      public static MemorySegment allocate(Arena arena)
      {
         return arena.allocate(SIZE, 8);
      }

      public static void set(MemorySegment timespec, long tvSec, long tvNsec)
      {
         timespec.set(JAVA_LONG, 0, tvSec);
         timespec.set(JAVA_LONG, 8, tvNsec);
      }
   }

   /**
    * Accessors for a 64-bit {@code struct kevent} laid out in native memory:
    * <pre>
    * struct kevent {
    *     uintptr_t ident;  // offset  0
    *     int16_t   filter; // offset  8
    *     uint16_t  flags;  // offset 10
    *     uint32_t  fflags; // offset 12
    *     intptr_t  data;   // offset 16
    *     void      *udata; // offset 24
    * };                    // size   32
    * </pre>
    */
   public static final class Kevent
   {
      public static final long SIZE = 32;

      private static final long IDENT_OFFSET = 0;
      private static final long FILTER_OFFSET = 8;
      private static final long FLAGS_OFFSET = 10;
      private static final long FFLAGS_OFFSET = 12;
      private static final long DATA_OFFSET = 16;
      private static final long UDATA_OFFSET = 24;

      private Kevent() {
      }

      public static MemorySegment allocateArray(Arena arena, int count)
      {
         return arena.allocate(SIZE * count, 8);
      }

      /** Returns a view of the {@code index}-th struct in an array of kevents. */
      public static MemorySegment at(MemorySegment keventArray, int index)
      {
         return keventArray.asSlice(SIZE * index, SIZE);
      }

      public static void EV_SET(MemorySegment kevent, long ident, int filter, int flags, int fflags, long data, long udata)
      {
         kevent.set(JAVA_LONG, IDENT_OFFSET, ident);
         kevent.set(JAVA_SHORT, FILTER_OFFSET, (short) filter);
         kevent.set(JAVA_SHORT, FLAGS_OFFSET, (short) flags);
         kevent.set(JAVA_INT, FFLAGS_OFFSET, fflags);
         kevent.set(JAVA_LONG, DATA_OFFSET, data);
         kevent.set(JAVA_LONG, UDATA_OFFSET, udata);
      }

      public static long getIdent(MemorySegment kevent)
      {
         return kevent.get(JAVA_LONG, IDENT_OFFSET);
      }

      public static int getFilter(MemorySegment kevent)
      {
         return kevent.get(JAVA_SHORT, FILTER_OFFSET);
      }

      public static int getFlags(MemorySegment kevent)
      {
         return kevent.get(JAVA_SHORT, FLAGS_OFFSET) & 0xffff;
      }

      public static int getFFlags(MemorySegment kevent)
      {
         return kevent.get(JAVA_INT, FFLAGS_OFFSET);
      }

      public static long getData(MemorySegment kevent)
      {
         return kevent.get(JAVA_LONG, DATA_OFFSET);
      }

      public static long getUdata(MemorySegment kevent)
      {
         return kevent.get(JAVA_LONG, UDATA_OFFSET);
      }

      /* actions */
      public static final int EV_ADD = 0x0001; /* add event to kq (implies enable) */
      public static final int EV_DELETE = 0x0002; /* delete event from kq */
      public static final int EV_ENABLE = 0x0004; /* enable event */
      public static final int EV_DISABLE = 0x0008; /* disable event (not reported) */
      public static final int EV_RECEIPT = 0x0040; /* force EV_ERROR on success, data == 0 */

      /* flags */
      public static final int EV_ONESHOT = 0x0010; /* only report one occurrence */
      public static final int EV_CLEAR = 0x0020; /* clear event state after reporting */
      public static final int EV_DISPATCH = 0x0080; /* disable event after reporting */

      public static final int EV_SYSFLAGS = 0xF000; /* reserved by system */
      public static final int EV_FLAG0 = 0x1000; /* filter-specific flag */
      public static final int EV_FLAG1 = 0x2000; /* filter-specific flag */

      /* returned values */
      public static final int EV_EOF = 0x8000; /* EOF detected */
      public static final int EV_ERROR = 0x4000; /* error, data contains errno */

      /* filters */
      public static final int EVFILT_READ = (-1);
      public static final int EVFILT_WRITE = (-2);
      public static final int EVFILT_AIO = (-3); /* attached to aio requests */
      public static final int EVFILT_VNODE = (-4); /* attached to vnodes */
      public static final int EVFILT_PROC = (-5); /* attached to struct proc */
      public static final int EVFILT_SIGNAL = (-6); /* attached to struct proc */
      public static final int EVFILT_TIMER = (-7); /* timers */
      public static final int EVFILT_MACHPORT = (-8); /* Mach portsets */
      public static final int EVFILT_FS = (-9); /* Filesystem events */
      public static final int EVFILT_USER = (-10); /* User events */
      /* (-11) unused */
      public static final int EVFILT_VM = (-12); /* Virtual memory events */

      /* data/hint fflags for EVFILT_PROC */
      public static final int NOTE_EXIT = 0x80000000; /* process exited */
      public static final int NOTE_REAP = 0x10000000; /* process exited */
      public static final int NOTE_EXITSTATUS = 0x04000000; /* exit status to be returned, valid for child process only */
   }
}
