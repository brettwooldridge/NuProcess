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

package com.zaxxer.nuprocess.internal;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class LibC
{
   static {
      if (System.getProperty("os.name").toLowerCase().contains("mac") || System.getProperty("os.name").toLowerCase().contains("freebsd")) {
         O_NONBLOCK = 0x0004; // MacOS X, Freebsd

         Native.register(NativeLibrary.getProcess());
      }
      else {
         O_NONBLOCK = 2048; // Linux

         Map<String, Object> options = new HashMap<>();
         options.put(Library.OPTION_ALLOW_OBJECTS, Boolean.TRUE);
         Native.register(NativeLibrary.getInstance("java", options));
         Native.register(NativeLibrary.getProcess());
      }
   }

   public static native int pipe(int[] fildes);

   public static native int fcntl(int fildes, int cmd);

   public static native int fcntl(int fildes, int cmd, long argO);

   public static native int close(int fildes);

   public static native int write(int fildes, ByteBuffer buf, int nbyte);

   public static native int read(int fildes, ByteBuffer buf, int nbyte);

   public static native int getpid();

   public static native int kill(int pid, int sig);

   public static native int waitpid(int pid, IntByReference status, int options);

   public static native int waitid(int idtype_t, int id_t, Pointer siginfo_t, int options);

   public static native int posix_spawnattr_init(Pointer posix_spawnattr_t);

   public static native int posix_spawnattr_destroy(Pointer posix_spawnattr_t);

   public static native int posix_spawnattr_setflags(Pointer posix_spawnattr_t, short flags);

   public static native int posix_spawn_file_actions_init(Pointer posix_spawn_file_actions_t);

   public static native int posix_spawn_file_actions_destroy(Pointer posix_spawn_file_actions_t);

   public static native int posix_spawn_file_actions_addclose(Pointer actions, int filedes);

   public static native int posix_spawn_file_actions_adddup2(Pointer actions, int fildes, int newfildes);

   public static native int posix_spawnp(IntByReference restrict_pid, String restrict_path, Pointer file_actions,
                                         Pointer /*const posix_spawnattr_t*/ restrict_attrp, StringArray /*String[]*/ argv, Pointer /*String[]*/ envp);

   public static native Pointer signal(int signal, Pointer func);

   public static native int chdir(String path);

   public static native String getcwd(Pointer buf, int size);

   // from /usr/include/sys/syscall.h
   // We can't use JNA direct mapping for syscall(), since it takes varargs.
   public interface SyscallLibrary extends Library
   {
      public static final int SYS___pthread_chdir = 348;

      int syscall(int syscall_number, Object... args);
   }

   public static SyscallLibrary SYSCALL = (SyscallLibrary) Native.loadLibrary(Platform.C_LIBRARY_NAME, SyscallLibrary.class);

   public static final int F_GETFL = 3;
   public static final int F_SETFL = 4;

   public static final int O_NONBLOCK;

   // from /usr/include/asm-generic/errno-base.h
   public static final int ECHILD = 10; /* No child processes */

   // from /usr/include/sys/wait.h
   public static final int WNOHANG = 0x00000001;

   // from /usr/include/sys/spawn.h
   public static final short POSIX_SPAWN_START_SUSPENDED = 0x0080;
   public static final short POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000;

   // From /usr/include/sys/signal.h
   public static final int SIGKILL = 9;
   public static final int SIGTERM = 15;
   public static final int SIGCONT = 19;
   public static final int SIGUSR2 = 31;

   public static final Pointer SIG_IGN = Pointer.createConstant(1);

   /* If WIFEXITED(STATUS), the low-order 8 bits of the status.  */
   public static int WEXITSTATUS(int status)
   {
      return (((status) & 0xff00) >> 8);
   }

   /* If WIFSIGNALED(STATUS), the terminating signal.  */
   public static int WTERMSIG(int status)
   {
      return ((status) & 0x7f);
   }

   /* If WIFSTOPPED(STATUS), the signal that stopped the child.  */
   public static int WSTOPSIG(int status)
   {
      return WEXITSTATUS(status);
   }

   /* Nonzero if STATUS indicates normal termination.  */
   public static boolean WIFEXITED(int status)
   {
      return ((status) & 0x7f) == 0;
   }

   /* Nonzero if STATUS indicates termination by a signal.  */
   public static boolean WIFSIGNALED(int status)
   {
      return (((byte) (((status) & 0x7f) + 1) >> 1) > 0);
   }

   /* Nonzero if STATUS indicates the child is stopped.  */
   public static boolean WIFSTOPPED(int status)
   {
      return WTERMSIG(status) != 0;
   }

   public static int W_EXITCODE(int ret, int sig)
   {
      return ((ret) << 8 | (sig));
   }

   public static int W_STOPCODE(int sig)
   {
      return ((sig) << 8 | 0x7f);
   }

   public interface SignalFunction extends Callback
   {
      void invoke(int signal);
   }
}
