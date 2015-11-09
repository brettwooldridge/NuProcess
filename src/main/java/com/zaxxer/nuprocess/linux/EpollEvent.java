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

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Union;

public class EpollEvent extends Structure
{

   /*
       typedef union epoll_data
       {
         void *ptr;
         int fd;
         uint32_t u32;
         uint64_t u64;
       } epoll_data_t;
    
       struct epoll_event
       {
         uint32_t events;   // Epoll events
         epoll_data_t data; // User data variable
       };
   */

   public int events;
   public EpollData data;

   EpollEvent()
   {
      // per eventpoll.h, x86_64 has the same alignment as 32-bit
      // super(ALIGN_GNUC);
      super(ALIGN_NONE);

      data = new EpollData();
      data.setType("u64");
   }

   @SuppressWarnings("rawtypes")
   @Override
   protected List getFieldOrder()
   {
      return Arrays.asList("events", "data");
   }

   public static class EpollData extends Union
   {
      public Pointer ptr;
      public int fd;
      public int u32;
      public long u64;
   }

}
