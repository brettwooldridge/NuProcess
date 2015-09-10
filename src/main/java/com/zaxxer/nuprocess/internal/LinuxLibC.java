/*
 * Copyright (C) 2015 Ben Hamilton
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

package com.zaxxer.nuprocess.internal;

import com.sun.jna.Native;
import com.sun.jna.Platform;

public class LinuxLibC
{
   static {
      Native.register(Platform.C_LIBRARY_NAME);
   }

   // from /usr/include/sched.h
   public static native int unshare(int flags);

   // from /usr/include/bits/sched.h
   public static final int CLONE_FS = 0x00000200; /* Share or unshare cwd between threads / processes */
}
