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

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * @author Brett Wooldridge
 */
public final class Kevent
{
   public static String getEventName(int event) {
      switch (event) {
         case Kevent.EV_ADD:
            return "EV_ADD";
         case Kevent.EV_CLEAR:
            return "EV_CLEAR";
         case Kevent.EV_DELETE:
            return "EV_DELETE";
         case Kevent.EV_DISABLE:
            return "EV_DISABLE";
         case Kevent.EV_DISPATCH:
            return "EV_DISPATCH";
         case Kevent.EV_ENABLE:
            return "EV_ENABLE";
         case Kevent.EV_EOF:
            return "EV_EOF";
         case Kevent.EV_ERROR:
            return "EV_ERROR";
         case Kevent.EV_FLAG0:
            return "EV_FLAG0";
         case Kevent.EV_FLAG1:
            return "EV_FLAG1";
         case Kevent.EV_ONESHOT:
            return "EV_ONESHOT";
         case Kevent.EV_RECEIPT:
            return "EV_RECEIPT";
         case Kevent.EV_SYSFLAGS:
            return "EV_SYSFLAGS";
         case Kevent.EVFILT_AIO:
            return "EVFILT_AIO";
         case Kevent.EVFILT_FS:
            return "EVFILT_FS";
         case Kevent.EVFILT_MACHPORT:
            return "EVFILT_MACHPORT";
         case Kevent.EVFILT_PROC:
            return "EVFILT_PROC";
         case Kevent.EVFILT_READ:
            return "EVFILT_READ";
         case Kevent.EVFILT_SIGNAL:
            return "EVFILT_SIGNAL";
         case Kevent.EVFILT_TIMER:
            return "EVFILT_TIMER";
         case Kevent.EVFILT_USER:
            return "EVFILT_USER";
         case Kevent.EVFILT_VM:
            return "EVFILT_VM";
         case Kevent.EVFILT_VNODE:
            return "EVFILT_VNODE";
         case Kevent.EVFILT_WRITE:
            return "EVFILT_WRITE";
         default:
            return Integer.toString(event);
      }
   }

   private Pointer pointer;

   public Kevent() {
      long memory = Native.malloc(32);
      pointer = new Pointer(memory);
   }

   void free()
   {
      Native.free(Pointer.nativeValue(pointer));
   }

   void clear()
   {
      pointer.clear(32);
   }

   Pointer getPointer()
   {
      return pointer;
   }

   long getIdent()
   {
      return pointer.getLong(0);
   }

   void setIdent(long ident)
   {
      pointer.setLong(0, ident);
   }

   short getFilter()
   {
      return pointer.getShort(NativeLong.SIZE);
   }

   void setFilter(short filter)
   {
      pointer.setShort(NativeLong.SIZE, filter);
   }

   short getFlags()
   {
      return pointer.getShort(NativeLong.SIZE + 2);
   }

   void setFlags(short flags)
   {
      pointer.setShort(NativeLong.SIZE + 2, flags);
   }

   int getFilterFlags()
   {
      return pointer.getInt(NativeLong.SIZE + 2 + 2);
   }

   void setFilterFlags(int filterFlags)
   {
      pointer.setInt(NativeLong.SIZE + 2 + 2, filterFlags);
   }

   long getData()
   {
      return pointer.getLong(NativeLong.SIZE + 2 + 2 + 4);
   }

   void setData(long data)
   {
      pointer.setLong(NativeLong.SIZE + 2 + 2 + 4, data);
   }

   Pointer getUserData()
   {
      return pointer.getPointer(NativeLong.SIZE + 2 + 2 + 4 + NativeLong.SIZE);
   }

   void setUserData(Pointer ptr)
   {
      pointer.setPointer(NativeLong.SIZE + 2 + 2 + 4 + NativeLong.SIZE, ptr);
   }

   protected static Kevent EV_SET(Kevent kev, long ident, int filter, int flags, int fflags, long data, Pointer udata)
   {
      kev.setIdent(ident);
      kev.setFilter((short) filter);
      kev.setFlags((short) flags);
      kev.setFilterFlags(fflags);
      kev.setData(data);
      kev.setUserData(udata);

      return kev;
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
