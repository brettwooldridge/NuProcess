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

import com.sun.jna.Native;

/**
 * @author Brett Wooldridge
 */
public class LibEpoll
{
   static {
      Native.register("c");
   }

   public static native int sigignore(int signal);

   public static native int epoll_create(int size);

   public static native int epoll_ctl(int epfd, int op, int fd, EpollEvent event);

   // technically, events should be an array, but we only ever call with maxevents = 1
   public static native int epoll_wait(int epfd, EpollEvent events, int maxevents, int timeout);

   public static final int SIGPIPE = 13;
   
   /* from /usr/include/sys/epoll.h */
   public static final int EPOLL_CTL_ADD = 1; /* Add a file decriptor to the interface.  */
   public static final int EPOLL_CTL_DEL = 2; /* Change file decriptor epoll_event structure.  */
   public static final int EPOLL_CTL_MOD = 3; /* Remove a file decriptor from the interface.  */

   public static final int EPOLLIN = 0x001;
   public static final int EPOLLOUT = 0x004;
   public static final int EPOLLERR = 0x008;
   public static final int EPOLLHUP = 0x010;
   public static final int EPOLLRDHUP = 0x2000;
   public static final int EPOLLONESHOT = (1 << 30);
   public static final int EPOLLET = (1 << 31);
}
