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

import java.util.Arrays;
import java.util.List;

import com.sun.jna.*;

class EpollEvent
{
   private static final int eventsOffset;
   private static final int fdOffset;
   private static final int size;

   static {
      EpollEventPrototype event = new EpollEventPrototype();
      eventsOffset = event.getFieldOffset("events");
      fdOffset = event.getFieldOffset("data");
      size = event.size();
   }

   private final Pointer pointer;

   EpollEvent() {
      pointer = new Memory(size);
   }

   int getEvents() {
      return pointer.getInt(eventsOffset);
   }

   void setEvents(final int mask) {
      pointer.setInt(eventsOffset, mask);
   }

   void setFileDescriptor(final int fd) {
      pointer.setInt(fdOffset, fd);
   }

   int getFileDescriptor() {
      return pointer.getInt(fdOffset);
   }

   Pointer getPointer() {
      return pointer;
   }

   int size() {
      return size;
   }

   public static class EpollEventPrototype extends Structure
   {
      /*
          struct epoll_event
          {
            uint32_t events;   // Epoll events
            epoll_data_t data; // User data variable
          } __EPOLL_PACKED;

          sizeof(struct epoll_event) is 12 on x86 and x86_64, but is 16 on other 64-bit platforms
      */

      public int events;
      public EpollData data;

      EpollEventPrototype() {
         super(detectAlignment());

         data = new EpollData();
         data.setType("fd");
      }

      int getFieldOffset(String field)
      {
         return fieldOffset(field);
      }

      @SuppressWarnings("rawtypes")
      @Override
      protected List<String> getFieldOrder() {
         return Arrays.asList("events", "data");
      }

      /**
       * Uses the OS architecture to reproduce the following logic from the epoll header:
       * <code><pre>
       * #ifdef __x86_64__
       * #define EPOLL_PACKED __attribute__((packed))
       * #else
       * #define EPOLL_PACKED
       * #endif
       * </pre></code>
       *
       * On x86-64 (amd64) platforms, {@code ALIGN_NONE} is used (to emulate {@code __attribute__((packed))}),
       * and on all other platforms {@code ALIGN_GNUC} is used.
       */
      private static int detectAlignment() {
         return Platform.isIntel() && Platform.is64Bit() ? ALIGN_NONE : ALIGN_GNUC;
      }

      /*
          typedef union epoll_data
          {
            void *ptr;
            int fd;
            uint32_t u32;
            uint64_t u64;
          } epoll_data_t;
      */

      @SuppressWarnings("unused") // unused fields are part of the union's C definition
      public static class EpollData extends Union {
         // technically this union should have a "Pointer ptr" field, but, for how EpollData is actually
         // used, only referencing the "fd" field, it's nothing but overhead. JNA will end up constructing
         // them as part of ProcessEpoll's execution, but they never get used
         //public Pointer ptr;
         public int fd;
         public int u32;
         public long u64; // must be included to make this union's size 8 bytes
      }
   }
}
