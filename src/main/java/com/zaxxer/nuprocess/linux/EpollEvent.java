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
import com.sun.jna.Pointer;

public class EpollEvent
{
    private Pointer pointer;

    EpollEvent()
    {
        long memory = Native.malloc(16);
        pointer = new Pointer(memory);
    }

    void free()
    {
        Native.free(Pointer.nativeValue(pointer));
    }

    void clear()
    {
        pointer.clear(12);
    }

    Pointer getPointer()
    {
        return pointer;
    }

    int getEvents()
    {
        return pointer.getInt(0);
    }

    void setEvents(int events)
    {
        pointer.setInt(0, events);
    }

    int getFd()
    {
        return pointer.getInt(4);
    }

    void setFd(int fd)
    {
        pointer.setInt(4, fd);
    }

    int getUnused()
    {
        return pointer.getInt(8);
    }

    void setUnused(int unused)
    {
        pointer.setInt(8, unused);
    }

    /* from /usr/include/sys/epoll.h */
    public static final int EPOLL_CTL_ADD = 1; /* Add a file decriptor to the interface.  */
    public static final int EPOLL_CTL_DEL = 2; /* Change file decriptor epoll_event structure.  */
    public static final int EPOLL_CTL_MOD = 3; /* Remove a file decriptor from the interface.  */

    public static final int EPOLLIN      = 0x001;
    public static final int EPOLLOUT     = 0x004;
    public static final int EPOLLERR     = 0x008;
    public static final int EPOLLHUP     = 0x010;
    public static final int EPOLLRDHUP   = 0x2000;
    public static final int EPOLLONESHOT = (1 << 30);
    public static final int EPOLLET      = (1 << 31);
}
