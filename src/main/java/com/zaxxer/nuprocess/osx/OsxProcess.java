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

import java.nio.file.Path;
import java.util.List;

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

/**
 * @author Brett Wooldridge
 */
public class OsxProcess extends BasePosixProcess
{
   static {
      for (int i = 0; i < processors.length; i++) {
         processors[i] = new ProcessKqueue();
      }

      // Setup a private signal for waking up the kqueue processing threads
      LibC.signal(LibC.SIGUSR2, LibC.SIG_IGN);
   }

   public OsxProcess(NuProcessHandler processListener) {
      super(processListener);
   }

   void stdinClose()
   {
      int fd = stdin.getAndSet(-1);
      if (fd != -1) {
         LibC.close(fd);
      }
   }

   public NuProcess start(List<String> command, String[] environment, Path cwd)
   {
      callPreStart();

      String[] commands = command.toArray(new String[command.size()]);

      Pointer posix_spawn_file_actions = createPipes();

      Pointer posix_spawnattr = new Memory(Pointer.SIZE);

      try {
         int rc = LibC.posix_spawnattr_init(posix_spawnattr);
         checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

         short flags = 0;
         // Start the spawned process in suspended mode
         flags = LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT;
         LibC.posix_spawnattr_setflags(posix_spawnattr, flags);

         IntByReference restrict_pid = new IntByReference();
         StringArray commandsArray = new StringArray(commands);
         StringArray environmentArray = new StringArray(environment);
         if (cwd != null) {
            rc = spawnOsxWithCwd(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray, cwd);
         }
         else {
            rc = LibC.posix_spawnp(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray);
         }

         pid = restrict_pid.getValue();

         initializeBuffers();

         checkReturnCode(rc, "Invocation of posix_spawn() failed");

         afterStart();

         registerProcess();

         callStart();

         // Signal the spawned process to continue (unsuspend)
         LibC.kill(pid, LibC.SIGCONT);
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

   @Override
   protected Pointer createPosixSpawnFileActions()
   {
      return new Memory(Pointer.SIZE);
   }

   private int spawnOsxWithCwd(IntByReference restrict_pid, String restrict_path, Pointer file_actions, Pointer /*const posix_spawnattr_t*/ restrict_attrp,
                               StringArray /*String[]*/ argv, Pointer /*String[]*/ envp, Path cwd)
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
}
