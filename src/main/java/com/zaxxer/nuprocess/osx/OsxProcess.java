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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.internal.LibC.SyscallLibrary;
import com.zaxxer.nuprocess.internal.ReferenceCountedFileDescriptor;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Brett Wooldridge
 */
class OsxProcess extends BasePosixProcess
{
   private volatile int stdinWidow;
   private volatile int stdoutWidow;
   private volatile int stderrWidow;

   static {
      for (int i = 0; i < processors.length; i++) {
         processors[i] = new ProcessKqueue();
      }

      // Setup a private signal for waking up the kqueue processing threads
      LibC.signal(LibC.SIGUSR2, LibC.SIG_IGN);
   }

   OsxProcess(NuProcessHandler processListener) {
      super(processListener);
   }

   @Override
   public NuProcess start(List<String> command, String[] environment, Path cwd) {
      callPreStart();

      String[] commands = command.toArray(new String[0]);

      Pointer posix_spawn_file_actions = createPipes();
      Pointer posix_spawnattr = createPosixSpawnAttributes();

      try {
         int rc = LibC.posix_spawnattr_init(posix_spawnattr);
         checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

         LibC.posix_spawnattr_setflags(posix_spawnattr, (short)(LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT));

         IntByReference restrict_pid = new IntByReference();
         StringArray commandsArray = new StringArray(commands);
         StringArray environmentArray = new StringArray(environment);
         if (cwd != null) {
            rc = spawnWithCwd(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray, cwd);
         }
         else {
            rc = LibC.posix_spawnp(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray);
         }

         pid = restrict_pid.getValue();

         initializeBuffers();

         if (!checkLaunch()) {
            return null;
         }

         checkReturnCode(rc, "Invocation of posix_spawn() failed");

         afterStart();

         registerProcess();

         callStart();

         singleProcessContinue();
      }
      catch (RuntimeException re) {
         // TODO remove from event processor pid map?
         re.printStackTrace(System.err);
         onExit(Integer.MIN_VALUE);
         return null;
      }
      finally {
         LibC.posix_spawnattr_destroy(posix_spawnattr);
         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

         // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
         LibC.close(stdinWidow);
         LibC.close(stdoutWidow);
         LibC.close(stderrWidow);
      }

      return this;
   }

   private int spawnWithCwd(final IntByReference restrict_pid,
                            final String restrict_path,
                            final Pointer file_actions,
                            final Pointer /*const posix_spawnattr_t*/ restrict_attrp,
                            final StringArray /*String[]*/ argv,
                            final Pointer /*String[]*/ envp,
                            final Path cwd)
   {
      int cwdBufSize = 1024;
      long peer = Native.malloc(cwdBufSize);
      Pointer oldCwd = new Pointer(peer);
      LibC.getcwd(oldCwd, cwdBufSize);
      String newCwd = cwd.toAbsolutePath().toString();
      int rc = LibC.SYSCALL.syscall(SyscallLibrary.SYS___pthread_chdir, newCwd);
      checkReturnCode(rc, "syscall(SYS__pthread_chdir) failed to set current directory");

      try {
         return LibC.posix_spawnp(restrict_pid, restrict_path, file_actions, restrict_attrp, argv, envp);
      }
      finally {
         rc = LibC.SYSCALL.syscall(SyscallLibrary.SYS___pthread_chdir, oldCwd);
         Native.free(Pointer.nativeValue(oldCwd));
         checkReturnCode(rc, "syscall(SYS__pthread_chdir) failed to restore current directory");
      }
   }

   private void singleProcessContinue()
   {
      // Signal the spawned process to continue (unsuspend)
      LibC.kill(pid, LibC.SIGCONT);
   }

   private Pointer createPipes()
   {
      int rc;

      int[] in = new int[2];
      int[] out = new int[2];
      int[] err = new int[2];

      Pointer posix_spawn_file_actions = createPosixSpawnFileActions();

      try {
         rc = LibC.pipe(in);
         checkReturnCode(rc, "Create stdin pipe() failed");
         rc = LibC.pipe(out);
         checkReturnCode(rc, "Create stdout pipe() failed");

         rc = LibC.pipe(err);
         checkReturnCode(rc, "Create stderr pipe() failed");

         // Create spawn file actions
         rc = LibC.posix_spawn_file_actions_init(posix_spawn_file_actions);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_init() failed");

         // Dup the reading end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, in[0], 0);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, in[1]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         stdin = new ReferenceCountedFileDescriptor(in[1]);
         stdinWidow = in[0];

         // Dup the writing end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, out[1], 1);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, out[0]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         stdout = new ReferenceCountedFileDescriptor(out[0]);
         stdoutWidow = out[1];

         // Dup the writing end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, err[1], 2);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, err[0]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         stderr = new ReferenceCountedFileDescriptor(err[0]);
         stderrWidow = err[1];

         setNonBlocking(in, out, err);

         return posix_spawn_file_actions;
      }
      catch (RuntimeException e) {
         e.printStackTrace(System.err);

         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
         initFailureCleanup(in, out, err);
         throw e;
      }
   }

   private Pointer createPosixSpawnFileActions()
   {
      return new Memory(Pointer.SIZE);
   }

   private Pointer createPosixSpawnAttributes()
   {
      return new Memory(Pointer.SIZE);
   }

   private void setNonBlocking(int[] in, int[] out, int[] err)
   {
      int rc = LibC.fcntl(in[1], LibC.F_SETFL, LibC.fcntl(in[1], LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stdin handle failed");
      rc = LibC.fcntl(out[0], LibC.F_SETFL, LibC.fcntl(out[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stdout handle failed");
      rc = LibC.fcntl(err[0], LibC.F_SETFL, LibC.fcntl(err[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stderr handle failed");
   }

   private void initFailureCleanup(int[] in, int[] out, int[] err)
   {
      Set<Integer> unique = new HashSet<>();
      if (in != null) {
         unique.add(in[0]);
         unique.add(in[1]);
      }

      if (out != null) {
         unique.add(out[0]);
         unique.add(out[1]);
      }

      if (err != null) {
         unique.add(err[0]);
         unique.add(err[1]);
      }

      for (int fildes : unique) {
         if (fildes != 0) {
            LibC.close(fildes);
         }
      }
   }
}
