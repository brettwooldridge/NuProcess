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

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;

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

          On x86, __EPOLL_PACKED is:    #define __EPOLL_PACKED
          On x86_64, __EPOLL_PACKED is: #define __EPOLL_PACKED __attribute__ ((__packed__))

          sizeof(struct epoll_event) is 12 on x86 and x86_64
      */

      public int events;
      public EpollData data;

      EpollEventPrototype() {
         // per bits/epoll.h, epoll_event is created with __attribute__ ((__packed__)), which disables
         // applying padding to optimize alignment. epoll_event is memory-aligned on 32-bit platforms,
         // but not on 64-bit platforms (i.e. it uses 32-bit alignment on 64-bit platforms)
         super(ALIGN_GNUC); // super(ALIGN_NONE);

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
