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
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * @author Brett Wooldridge
 */
public class LibKevent
{
    static
    {
        Native.register("c");
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
}
