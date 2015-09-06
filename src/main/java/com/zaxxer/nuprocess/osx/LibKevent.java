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

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * @author Brett Wooldridge
 */
public class LibKevent
{
   static {
      Native.register(NativeLibrary.getProcess());
   }

   public static native int kqueue();

   public static native int kevent(int kq, Pointer changeList, int nchanges, Pointer eventList, int nevents, TimeSpec timespec);

   public static class TimeSpec extends Structure
   {
      public long tv_sec;
      public long tv_nsec;

      @SuppressWarnings("rawtypes")
      @Override
      protected List getFieldOrder()
      {
         return Arrays.asList("tv_sec", "tv_nsec");
      }
   }

   public static class Kevent extends Structure
   {
      public NativeLong ident;
      public short filter;
      public short flags;
      public int fflags;
      public NativeLong data;
      public Pointer udata;

      @SuppressWarnings("rawtypes")
      @Override
      protected List getFieldOrder()
      {
         return Arrays.asList("ident", "filter", "flags", "fflags", "data", "udata");
      }

      Kevent EV_SET(long ident, int filter, int flags, int fflags, long data, Pointer udata)
      {
         this.ident.setValue(ident);
         this.filter = (short) filter;
         this.flags = (short) flags;
         this.fflags = fflags;
         this.data.setValue(data);
         this.udata = udata;
         return this;
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
