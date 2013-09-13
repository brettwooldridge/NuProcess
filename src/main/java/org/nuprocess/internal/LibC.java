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

package org.nuprocess.internal;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

public class LibC
{
    static
    {
        Native.register("c");

        if (System.getProperty("os.name").toLowerCase().contains("mac"))
        {
            O_NONBLOCK = 0x0004;  // MacOS X
        }
        else
        {
            O_NONBLOCK = 2048; // Linux
        }
    }

    public static native int pipe(int[] fildes);

    public static native int fcntl(int fildes, int cmd);

    public static native int fcntl(int fildes, int cmd, long argO);

    public static native int close(int fildes);

    public static native int write(int fildes, Pointer buf, int nbyte);

    public static native int read(int fildes, Pointer buf, int nbyte);

    public static native int kill(int pid, int sig);

    public static native int waitpid(int pid, IntByReference status, int options);

    public static native int posix_spawnattr_init(Pointer posix_spawnattr_t);

    public static native int posix_spawnattr_destroy(Pointer posix_spawnattr_t);

    public static native int posix_spawnattr_setflags(Pointer posix_spawnattr_t, short flags);

    public static native int posix_spawn_file_actions_init(Pointer posix_spawn_file_actions_t);

    public static native int posix_spawn_file_actions_destroy(Pointer posix_spawn_file_actions_t);

    public static native int posix_spawn_file_actions_addclose(Pointer actions, int filedes);

    public static native int posix_spawn_file_actions_adddup2(Pointer actions, int fildes, int newfildes);

    // public static native int posix_spawn_file_actions_addinherit_np(PointerByReference actions, int filedes);

    public static native int posix_spawn(IntByReference restrict_pid, String restrict_path, Pointer file_actions,
                                         Pointer /*const posix_spawnattr_t*/ restrict_attrp, StringArray /*String[]*/ argv,
                                         Pointer /*String[]*/ envp);

    public static final int F_GETFL = 3;
    public static final int F_SETFL = 4;

    public static final int O_NONBLOCK;

    // from /usr/include/sys/wait.h
    public static final int WNOHANG =0x00000001;
    
    // from /usr/include/sys/spawn.h
    public static final short POSIX_SPAWN_START_SUSPENDED = 0x0080;
    public static final short POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000;

    // From /usr/include/sys/signal.h
    public static final int SIGKILL = 9;
    public static final int SIGTERM = 15;
    public static final int SIGCONT = 19;
}
