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

package com.zaxxer.nuprocess.linux;

import com.zaxxer.nuprocess.internal.FfmSupport;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * FFM bindings for the Linux epoll facility.
 *
 * @author Brett Wooldridge
 */
public class LibEpoll
{
   private static final SymbolLookup LIBC = FfmSupport.LINKER.defaultLookup();

   private static final MethodHandle EPOLL_CREATE =
      FfmSupport.downcall(LIBC, "epoll_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
   private static final MethodHandle EPOLL_CTL =
      FfmSupport.downcall(LIBC, "epoll_ctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
   private static final MethodHandle EPOLL_WAIT =
      FfmSupport.downcall(LIBC, "epoll_wait", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));

   public static int epoll_create(int size)
   {
      try {
         return (int) EPOLL_CREATE.invokeExact(FfmSupport.captureState(), size);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   /**
    * @param event the address of an {@link EpollEvent}, or {@link MemorySegment#NULL}
    */
   public static int epoll_ctl(int epfd, int op, int fd, MemorySegment event)
   {
      try {
         return (int) EPOLL_CTL.invokeExact(FfmSupport.captureState(), epfd, op, fd, event);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   // We only ever call this API with maxevents=1.  However, if calling with maxevents > 1,
   // care must be taken to ensure that the "events" segment actually points to a
   // contiguous block of memory large enough to handle maxevents number of EpollEvent
   // mappings.
   public static int epoll_wait(int epfd, MemorySegment events, int maxevents, int timeout)
   {
      try {
         return (int) EPOLL_WAIT.invokeExact(FfmSupport.captureState(), epfd, events, maxevents, timeout);
      }
      catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }

   public static int getLastError()
   {
      return FfmSupport.getLastError();
   }

   public static final int SIGPIPE = 13;

   /* from /usr/include/sys/epoll.h */
   public static final int EPOLL_CTL_ADD = 1; /* Add a file descriptor to the interface.  */
   public static final int EPOLL_CTL_DEL = 2; /* Remove a file descriptor from the interface.  */
   public static final int EPOLL_CTL_MOD = 3; /* Change file descriptor epoll_event structure.  */

   public static final int EPOLLIN = 0x001;
   public static final int EPOLLOUT = 0x004;
   public static final int EPOLLERR = 0x008;
   public static final int EPOLLHUP = 0x010;
   public static final int EPOLLRDHUP = 0x2000;
   public static final int EPOLLONESHOT = (1 << 30);
}
