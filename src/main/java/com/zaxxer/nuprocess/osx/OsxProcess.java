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

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.IEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * @author Brett Wooldridge
 */
class OsxProcess extends BasePosixProcess
{
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

      try (Arena spawnArena = Arena.ofConfined()) {
         // Arena memory is zero-initialized, so destroying a never-initialized attr or
         // file actions struct is benign (the embedded pointer is NULL)
         MemorySegment posix_spawn_file_actions = spawnArena.allocate(LibC.POSIX_SPAWN_FILE_ACTIONS_T_SIZE, 8);
         MemorySegment posix_spawnattr = spawnArena.allocate(LibC.POSIX_SPAWNATTR_T_SIZE, 8);

         createPosixPipes(posix_spawn_file_actions);

         try {
            int rc = prepareProcess(environment, cwd, commands, posix_spawn_file_actions, posix_spawnattr, spawnArena);
            checkReturnCode(rc, "Invocation of posix_spawn() failed");

            afterStart();

            // On macOS, registration can be immediately followed by queueing read handling for stdout
            // and stderr without risking a racy exit because processes are launched suspended and are
            // only resumed after NuProcessHandler.onStart is called
            registerProcess();
            myProcessor.queueRead(this);

            callStart();

            singleProcessContinue();
         }
         catch (RuntimeException e) {
            // TODO remove from event processor pid map?
            LOGGER.log(Level.WARNING, "Exception thrown from handler", e);
            onExit(Integer.MIN_VALUE);
            return null;
         }
         finally {
            LibC.posix_spawnattr_destroy(posix_spawnattr);
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

            // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
            closePipes();
         }
      }

      return this;
   }

    @Override
   public void run(List<String> command, String[] environment, Path cwd)
   {
      callPreStart();

      String[] commands = command.toArray(new String[0]);

      try (Arena spawnArena = Arena.ofConfined()) {
         MemorySegment posix_spawn_file_actions = spawnArena.allocate(LibC.POSIX_SPAWN_FILE_ACTIONS_T_SIZE, 8);
         MemorySegment posix_spawnattr = spawnArena.allocate(LibC.POSIX_SPAWNATTR_T_SIZE, 8);

         createPosixPipes(posix_spawn_file_actions);

         try {
            int rc = prepareProcess(environment, cwd, commands, posix_spawn_file_actions, posix_spawnattr, spawnArena);
            checkReturnCode(rc, "Invocation of posix_spawn() failed");

            afterStart();

            myProcessor = (IEventProcessor) new ProcessKqueue(this);

            callStart();

            singleProcessContinue();

            myProcessor.run();
         }
         catch (RuntimeException e) {
            // TODO remove from event processor pid map?
            LOGGER.log(Level.WARNING, "Exception thrown from handler", e);
            onExit(Integer.MIN_VALUE);
         }
         finally {
            LibC.posix_spawnattr_destroy(posix_spawnattr);
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

            // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
            closePipes();
         }
      }
   }

   private int prepareProcess(String[] environment, Path cwd, String[] commands,
                              MemorySegment posix_spawn_file_actions, MemorySegment posix_spawnattr, Arena spawnArena)
   {
      int rc = LibC.posix_spawnattr_init(posix_spawnattr);
      checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

      LibC.posix_spawnattr_setflags(posix_spawnattr, (short)(LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT));

      if (cwd != null) {
         // Have the child process change its working directory before exec'ing; this
         // replaces the old SYS___pthread_chdir syscall hack (addchdir_np is available
         // on macOS 10.15+, well below the minimum for any FFM-capable JVM)
         rc = LibC.posix_spawn_file_actions_addchdir_np(posix_spawn_file_actions, spawnArena.allocateFrom(cwd.toAbsolutePath().toString()));
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addchdir_np() failed");
      }

      int[] restrict_pid = new int[1];
      rc = LibC.posix_spawnp(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commands, environment);

      pid = restrict_pid[0];

      initializeBuffers();

      return rc;
   }

   private void closePipes()
   {
      LibC.close(stdinWidow);
      LibC.close(stdoutWidow);
      LibC.close(stderrWidow);
   }

   private void singleProcessContinue()
   {
      // Signal the spawned process to continue (unsuspend)
      LibC.kill(pid, LibC.SIGCONT);
   }

   private void createPosixPipes(MemorySegment posix_spawn_file_actions)
   {
      int rc;

      try {
         int[] fds = createPipes();

         // Create spawn file actions
         rc = LibC.posix_spawn_file_actions_init(posix_spawn_file_actions);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_init() failed");

         // Dup the reading end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, stdinWidow, 0);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, fds[0]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         // Dup the writing end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, stdoutWidow, 1);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, fds[1]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         // Dup the writing end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, stderrWidow, 2);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, fds[2]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");
      }
      catch (RuntimeException e) {
         LOGGER.log(Level.WARNING, "Exception creating posix pipe actions", e);
         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
         throw e;
      }
   }
}
