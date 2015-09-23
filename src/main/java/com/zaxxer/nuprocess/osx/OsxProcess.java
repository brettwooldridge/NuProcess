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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
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

   @Override
   protected short getSpawnFlags()
   {
      // Start the spawned process in suspended mode
      return LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT;
   }

   @Override
   protected Pointer createPosixSpawnFileActions()
   {
      return new Memory(Pointer.SIZE);
   }

   @Override
   protected Pointer createPosixSpawnAttributes()
   {
      return new Memory(Pointer.SIZE);
   }

   @Override
   protected int spawnWithCwd(final IntByReference restrict_pid,
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

   @Override
   protected void singleProcessContinue()
   {
      // Signal the spawned process to continue (unsuspend)
      LibC.kill(pid, LibC.SIGCONT);
   }
}
