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

import com.zaxxer.nuprocess.internal.Constants.OperatingSystem;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import static com.zaxxer.nuprocess.internal.Constants.OS;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * FFM bindings for the POSIX libc functions used by NuProcess.
 */
@SuppressWarnings("WeakerAccess")
public class LibC
{
   private static final SymbolLookup LIBC = FfmSupport.LINKER.defaultLookup();

   private static final MethodHandle PIPE;
   private static final MethodHandle PIPE2; // Linux only, may be null
   private static final MethodHandle FCNTL2;
   private static final MethodHandle FCNTL3;
   private static final MethodHandle CLOSE;
   private static final MethodHandle READ;
   private static final MethodHandle WRITE;
   private static final MethodHandle GETPID;
   private static final MethodHandle KILL;
   private static final MethodHandle WAITPID;
   private static final MethodHandle SIGNAL;
   private static final MethodHandle POSIX_SPAWNP;
   private static final MethodHandle POSIX_SPAWNATTR_INIT;
   private static final MethodHandle POSIX_SPAWNATTR_DESTROY;
   private static final MethodHandle POSIX_SPAWNATTR_SETFLAGS;
   private static final MethodHandle POSIX_SPAWN_FILE_ACTIONS_INIT;
   private static final MethodHandle POSIX_SPAWN_FILE_ACTIONS_DESTROY;
   private static final MethodHandle POSIX_SPAWN_FILE_ACTIONS_ADDCLOSE;
   private static final MethodHandle POSIX_SPAWN_FILE_ACTIONS_ADDDUP2;
   private static final MethodHandle POSIX_SPAWN_FILE_ACTIONS_ADDCHDIR_NP;  // may be null on older libc
   private static final MethodHandle POSIX_SPAWN_FILE_ACTIONS_ADDCLOSEFROM_NP; // glibc >= 2.34 only, may be null

   static {
      if (OS == OperatingSystem.MAC) {
         O_NONBLOCK = 0x0004; // MacOS X, Freebsd
      }
      else {
         O_NONBLOCK = 2048; // Linux
      }

      PIPE = FfmSupport.downcall(LIBC, "pipe", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      PIPE2 = (OS == OperatingSystem.LINUX)
         ? FfmSupport.downcallOptional(LIBC, "pipe2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
         : null;
      // fcntl() is variadic; both fixed-arity uses must be linked as such
      FCNTL2 = FfmSupport.downcall(LIBC, "fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT),
                                   Linker.Option.firstVariadicArg(2));
      FCNTL3 = FfmSupport.downcall(LIBC, "fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT),
                                   Linker.Option.firstVariadicArg(2));
      CLOSE = FfmSupport.downcall(LIBC, "close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
      READ = FfmSupport.downcall(LIBC, "read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
      WRITE = FfmSupport.downcall(LIBC, "write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
      GETPID = FfmSupport.downcall(LIBC, "getpid", FunctionDescriptor.of(JAVA_INT));
      KILL = FfmSupport.downcall(LIBC, "kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
      WAITPID = FfmSupport.downcall(LIBC, "waitpid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
      SIGNAL = FfmSupport.downcall(LIBC, "signal", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
      POSIX_SPAWNP = FfmSupport.downcall(LIBC, "posix_spawnp",
                                         FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
      POSIX_SPAWNATTR_INIT = FfmSupport.downcall(LIBC, "posix_spawnattr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      POSIX_SPAWNATTR_DESTROY = FfmSupport.downcall(LIBC, "posix_spawnattr_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      POSIX_SPAWNATTR_SETFLAGS = FfmSupport.downcall(LIBC, "posix_spawnattr_setflags", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT));
      POSIX_SPAWN_FILE_ACTIONS_INIT = FfmSupport.downcall(LIBC, "posix_spawn_file_actions_init", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      POSIX_SPAWN_FILE_ACTIONS_DESTROY = FfmSupport.downcall(LIBC, "posix_spawn_file_actions_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      POSIX_SPAWN_FILE_ACTIONS_ADDCLOSE = FfmSupport.downcall(LIBC, "posix_spawn_file_actions_addclose", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
      POSIX_SPAWN_FILE_ACTIONS_ADDDUP2 = FfmSupport.downcall(LIBC, "posix_spawn_file_actions_adddup2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
      POSIX_SPAWN_FILE_ACTIONS_ADDCHDIR_NP = FfmSupport.downcallOptional(LIBC, "posix_spawn_file_actions_addchdir_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      POSIX_SPAWN_FILE_ACTIONS_ADDCLOSEFROM_NP = FfmSupport.downcallOptional(LIBC, "posix_spawn_file_actions_addclosefrom_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
   }

   /**
    * The errno value captured by the most recent libc call made on this thread.
    */
   public static int getLastError()
   {
      return FfmSupport.getLastError();
   }

   /**
    * Creates a pipe. On Linux the pipe is created with {@code O_CLOEXEC} (via {@code pipe2})
    * so that descriptors do not leak into spawned children; the descriptors that a child
    * must inherit are dup2'ed to stdin/stdout/stderr by posix_spawn file actions, which
    * clears the close-on-exec flag on the duplicates. On macOS, {@code POSIX_SPAWN_CLOEXEC_DEFAULT}
    * provides equivalent behavior.
    */
   public static int pipe(int[] fildes)
   {
      try {
         MemorySegment fds = FfmSupport.scratch();
         int rc;
         if (PIPE2 != null) {
            rc = (int) PIPE2.invokeExact(FfmSupport.captureState(), fds, O_CLOEXEC_LINUX);
         }
         else {
            rc = (int) PIPE.invokeExact(FfmSupport.captureState(), fds);
         }
         fildes[0] = fds.get(JAVA_INT, 0);
         fildes[1] = fds.get(JAVA_INT, 4);
         return rc;
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawnattr_init(MemorySegment posix_spawnattr_t)
   {
      try {
         return (int) POSIX_SPAWNATTR_INIT.invokeExact(FfmSupport.captureState(), posix_spawnattr_t);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawnattr_destroy(MemorySegment posix_spawnattr_t)
   {
      try {
         return (int) POSIX_SPAWNATTR_DESTROY.invokeExact(FfmSupport.captureState(), posix_spawnattr_t);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawnattr_setflags(MemorySegment posix_spawnattr_t, short flags)
   {
      try {
         return (int) POSIX_SPAWNATTR_SETFLAGS.invokeExact(FfmSupport.captureState(), posix_spawnattr_t, flags);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawn_file_actions_init(MemorySegment actions)
   {
      try {
         return (int) POSIX_SPAWN_FILE_ACTIONS_INIT.invokeExact(FfmSupport.captureState(), actions);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawn_file_actions_destroy(MemorySegment actions)
   {
      try {
         return (int) POSIX_SPAWN_FILE_ACTIONS_DESTROY.invokeExact(FfmSupport.captureState(), actions);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawn_file_actions_addclose(MemorySegment actions, int filedes)
   {
      try {
         return (int) POSIX_SPAWN_FILE_ACTIONS_ADDCLOSE.invokeExact(FfmSupport.captureState(), actions, filedes);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int posix_spawn_file_actions_adddup2(MemorySegment actions, int fildes, int newfildes)
   {
      try {
         return (int) POSIX_SPAWN_FILE_ACTIONS_ADDDUP2.invokeExact(FfmSupport.captureState(), actions, fildes, newfildes);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   /**
    * Whether this platform's libc provides {@code posix_spawn_file_actions_addchdir_np}
    * (glibc 2.29+, musl 1.1.24+, macOS 10.15+).
    */
   public static boolean hasAddChdirFileAction()
   {
      return POSIX_SPAWN_FILE_ACTIONS_ADDCHDIR_NP != null;
   }

   public static int posix_spawn_file_actions_addchdir_np(MemorySegment actions, MemorySegment path)
   {
      if (POSIX_SPAWN_FILE_ACTIONS_ADDCHDIR_NP == null) {
         throw new UnsupportedOperationException("posix_spawn_file_actions_addchdir_np is not available in this platform's libc");
      }
      try {
         return (int) POSIX_SPAWN_FILE_ACTIONS_ADDCHDIR_NP.invokeExact(FfmSupport.captureState(), actions, path);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   /**
    * Whether this platform's libc provides {@code posix_spawn_file_actions_addclosefrom_np} (glibc 2.34+).
    */
   public static boolean hasAddCloseFromFileAction()
   {
      return POSIX_SPAWN_FILE_ACTIONS_ADDCLOSEFROM_NP != null;
   }

   public static int posix_spawn_file_actions_addclosefrom_np(MemorySegment actions, int lowfiledes)
   {
      if (POSIX_SPAWN_FILE_ACTIONS_ADDCLOSEFROM_NP == null) {
         throw new UnsupportedOperationException("posix_spawn_file_actions_addclosefrom_np is not available in this platform's libc");
      }
      try {
         return (int) POSIX_SPAWN_FILE_ACTIONS_ADDCLOSEFROM_NP.invokeExact(FfmSupport.captureState(), actions, lowfiledes);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   /**
    * Invokes {@code posix_spawnp}. On success the spawned process id is stored in
    * {@code restrict_pid[0]}.
    *
    * @param restrict_pid single-element array receiving the child pid
    * @param restrict_path the executable to spawn (resolved against PATH)
    * @param file_actions initialized {@code posix_spawn_file_actions_t}, or {@link MemorySegment#NULL}
    * @param restrict_attrp initialized {@code posix_spawnattr_t}, or {@link MemorySegment#NULL}
    * @param argv the argument vector, including the executable as element zero
    * @param envp the environment
    * @return 0 on success, otherwise an errno-style error code
    */
   public static int posix_spawnp(int[] restrict_pid, String restrict_path, MemorySegment file_actions,
                                  MemorySegment restrict_attrp, String[] argv, String[] envp)
   {
      try (Arena arena = Arena.ofConfined()) {
         MemorySegment pid = arena.allocate(JAVA_INT);
         MemorySegment path = arena.allocateFrom(restrict_path);
         MemorySegment argvArray = FfmSupport.toStringArray(arena, argv);
         MemorySegment envpArray = FfmSupport.toStringArray(arena, envp);
         int rc = (int) POSIX_SPAWNP.invokeExact(FfmSupport.captureState(), pid, path, file_actions, restrict_attrp, argvArray, envpArray);
         restrict_pid[0] = pid.get(JAVA_INT, 0);
         return rc;
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int fcntl(int fildes, int cmd)
   {
      try {
         return (int) FCNTL2.invokeExact(FfmSupport.captureState(), fildes, cmd);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int fcntl(int fildes, int cmd, int arg)
   {
      try {
         return (int) FCNTL3.invokeExact(FfmSupport.captureState(), fildes, cmd, arg);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int close(int fildes)
   {
      try {
         return (int) CLOSE.invokeExact(FfmSupport.captureState(), fildes);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   /**
    * Writes up to {@code nbyte} bytes from {@code buf}, starting at the buffer's current
    * position, to the given descriptor. The buffer's position is <em>not</em> updated.
    */
   public static int write(int fildes, ByteBuffer buf, int nbyte)
   {
      try {
         MemorySegment segment = MemorySegment.ofBuffer(buf);
         long rc = (long) WRITE.invokeExact(FfmSupport.captureState(), fildes, segment, (long) nbyte);
         return (int) rc;
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   /**
    * Reads up to {@code nbyte} bytes into {@code buf}, starting at the buffer's current
    * position. The buffer's position is <em>not</em> updated.
    */
   public static int read(int fildes, ByteBuffer buf, int nbyte)
   {
      try {
         MemorySegment segment = MemorySegment.ofBuffer(buf);
         long rc = (long) READ.invokeExact(FfmSupport.captureState(), fildes, segment, (long) nbyte);
         return (int) rc;
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int getpid()
   {
      try {
         return (int) GETPID.invokeExact(FfmSupport.captureState());
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static int kill(int pid, int sig)
   {
      try {
         return (int) KILL.invokeExact(FfmSupport.captureState(), pid, sig);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   /**
    * Invokes {@code waitpid}; on a successful wait the raw status is stored in {@code status[0]}.
    */
   public static int waitpid(int pid, int[] status, int options)
   {
      try {
         MemorySegment statusSegment = FfmSupport.scratch();
         int rc = (int) WAITPID.invokeExact(FfmSupport.captureState(), pid, statusSegment, options);
         status[0] = statusSegment.get(JAVA_INT, 0);
         return rc;
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   public static MemorySegment signal(int signal, MemorySegment func)
   {
      try {
         return (MemorySegment) SIGNAL.invokeExact(FfmSupport.captureState(), signal, func);
      }
      catch (Throwable t) {
         throw rethrow(t);
      }
   }

   static RuntimeException rethrow(Throwable t)
   {
      if (t instanceof RuntimeException) {
         throw (RuntimeException) t;
      }
      if (t instanceof Error) {
         throw (Error) t;
      }
      throw new RuntimeException(t);
   }

   public static final int F_GETFL = 3;
   public static final int F_SETFL = 4;

   public static final int O_NONBLOCK;
   private static final int O_CLOEXEC_LINUX = 0x80000; // 02000000 octal, from /usr/include/asm-generic/fcntl.h

   // from /usr/include/asm-generic/errno-base.h
   public static final int ESRCH = 3;   /* No such process */
   public static final int EINTR = 4;   /* Interrupted system call */
   public static final int ECHILD = 10; /* No child processes */

   // from /usr/include/sys/wait.h
   public static final int WNOHANG = 0x00000001;

   // from /usr/include/sys/spawn.h (macOS-specific flags)
   public static final short POSIX_SPAWN_START_SUSPENDED = 0x0080;
   public static final short POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000;

   // From /usr/include/sys/signal.h
   public static final int SIGKILL = 9;
   public static final int SIGTERM = 15;
   public static final int SIGPIPE = 13;
   public static final int SIGCONT = 19; // macOS value; only used on macOS
   public static final int SIGUSR2 = 31; // macOS value; only used on macOS

   public static final MemorySegment SIG_IGN = MemorySegment.ofAddress(1L);

   /**
    * Size in bytes of a buffer guaranteed to hold a {@code posix_spawn_file_actions_t}
    * (a pointer on macOS; an 80-byte struct on glibc).
    */
   public static final long POSIX_SPAWN_FILE_ACTIONS_T_SIZE = 128;

   /**
    * Size in bytes of a buffer guaranteed to hold a {@code posix_spawnattr_t}
    * (a pointer on macOS; a 336-byte struct on glibc).
    */
   public static final long POSIX_SPAWNATTR_T_SIZE = 512;

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
}
