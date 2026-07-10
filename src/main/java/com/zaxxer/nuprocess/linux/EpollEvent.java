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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A {@code struct epoll_event} laid out in native memory:
 * <pre>
 * struct epoll_event
 * {
 *   uint32_t events;   // Epoll events
 *   epoll_data_t data; // User data variable (union; only the 'fd' member is used here)
 * } __EPOLL_PACKED;
 * </pre>
 * On x86-64 the struct is declared with {@code __attribute__((packed))}, making it
 * 12 bytes with the data union at offset 4. On all other 64-bit architectures it has
 * natural alignment: 16 bytes with the data union at offset 8. On 32-bit architectures
 * it is 12 bytes with the data union at offset 4.
 */
class EpollEvent
{
   private static final int EVENTS_OFFSET = 0;
   private static final int DATA_OFFSET;
   private static final int SIZE;

   static {
      String arch = System.getProperty("os.arch").toLowerCase();
      boolean isIntel64 = "amd64".equals(arch) || "x86_64".equals(arch) || "x86-64".equals(arch);
      boolean is64Bit = arch.contains("64") || "s390x".equals(arch);

      if (is64Bit && !isIntel64) {
         DATA_OFFSET = 8;
         SIZE = 16;
      }
      else {
         DATA_OFFSET = 4;
         SIZE = 12;
      }
   }

   private final MemorySegment segment;

   EpollEvent() {
      segment = Arena.ofAuto().allocate(SIZE, 8);
   }

   int getEvents() {
      return segment.get(JAVA_INT, EVENTS_OFFSET);
   }

   void setEvents(final int mask) {
      segment.set(JAVA_INT, EVENTS_OFFSET, mask);
   }

   void setFileDescriptor(final int fd) {
      segment.set(JAVA_INT, DATA_OFFSET, fd);
   }

   int getFileDescriptor() {
      return segment.get(JAVA_INT, DATA_OFFSET);
   }

   MemorySegment getSegment() {
      return segment;
   }

   int size() {
      return SIZE;
   }
}
