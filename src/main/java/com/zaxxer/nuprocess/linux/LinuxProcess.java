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

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.IEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
   private final EpollEvent epollEvent;

   static {
      LibC.signal(LibC.SIGPIPE, LibC.SIG_IGN);

      // TODO: install signal handler for SIGCHLD, and call onExit() when received, call the default (JVM) hook if the PID is not ours

      for (int i = 0; i < processors.length; i++) {
         processors[i] = new ProcessEpoll();
      }
   }

   LinuxProcess(NuProcessHandler processListener) {
      super(processListener);

      epollEvent = new EpollEvent();
   }

   @Override
   public NuProcess start(List<String> command, String[] environment, Path cwd) {
      callPreStart();

      try {
         prepareProcess(command, environment, cwd);
         if (pid == -1) {
            return null;
         }

         initializeBuffers();

         afterStart();

         // Registration must happen prior to calling NuProcessHandler.onStart to allow handlers
         // to call wantWrite (which calls myProcessor.queueWrite)
         registerProcess();

         callStart();

         // Queueing read handling for stdout and stderr happens after start has been called
         // to ensure fast-exiting processes don't call NuProcessHandler.onExit before onStart
         myProcessor.queueRead(this);
      }
      catch (Exception e) {
         // TODO remove from event processor pid map?
         LOGGER.log(Level.WARNING, "Failed to start process", e);
         onExit(Integer.MIN_VALUE);
         return null;
      }

      return this;
   }

   @Override
   public void run(List<String> command, String[] environment, Path cwd)
   {
      callPreStart();

      try {
         prepareProcess(command, environment, cwd);
         if (pid == -1) {
            return;
         }

         initializeBuffers();

         afterStart();

         myProcessor = (IEventProcessor) new ProcessEpoll(this);

         callStart();

         myProcessor.run();
      }
      catch (Exception e) {
         LOGGER.log(Level.WARNING, "Failed to start process", e);
         onExit(Integer.MIN_VALUE);
      }
   }

   /**
    * An {@link EpollEvent} struct, which may be used when registering for events for this process. Each process has
    * its own struct to avoid concurrency issues in {@link ProcessEpoll#registerProcess} when multiple processes are
    * registered at once (e.g. multiple threads are all starting new processes concurrently).
    *
    * @return this process's {@link EpollEvent} struct
    */
   EpollEvent getEpollEvent() {
      return epollEvent;
   }

   /**
    * Spawns the child with {@code posix_spawnp}, the same mechanism the modern JDK uses
    * for {@link ProcessBuilder}. The pipes created by {@link #createPipes()} are opened
    * with {@code O_CLOEXEC} (see {@link LibC#pipe(int[])}), so only the descriptors
    * explicitly dup2'ed onto stdin/stdout/stderr survive into the child; where the libc
    * supports it (glibc 2.34+), all other descriptors are additionally closed with
    * {@code posix_spawn_file_actions_addclosefrom_np}.
    */
   private void prepareProcess(List<String> command, String[] environment, Path cwd) throws IOException
   {
      String[] cmdarray = command.toArray(new String[0]);

      createPipes();
      try (Arena spawnArena = Arena.ofConfined()) {
         MemorySegment fileActions = spawnArena.allocate(LibC.POSIX_SPAWN_FILE_ACTIONS_T_SIZE, 8);
         int rc = LibC.posix_spawn_file_actions_init(fileActions);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_init() failed");

         try {
            // Dup the child ends of the pipes onto stdin/stdout/stderr; dup2 clears the
            // close-on-exec flag on the duplicated descriptors
            rc = LibC.posix_spawn_file_actions_adddup2(fileActions, stdinWidow, 0);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

            rc = LibC.posix_spawn_file_actions_adddup2(fileActions, stdoutWidow, 1);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

            rc = LibC.posix_spawn_file_actions_adddup2(fileActions, stderrWidow, 2);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

            if (LibC.hasAddCloseFromFileAction()) {
               rc = LibC.posix_spawn_file_actions_addclosefrom_np(fileActions, 3);
               checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclosefrom_np() failed");
            }

            if (cwd != null) {
               if (!LibC.hasAddChdirFileAction()) {
                  throw new IOException("Setting a working directory requires posix_spawn_file_actions_addchdir_np (glibc 2.29+ or musl 1.1.24+)");
               }
               rc = LibC.posix_spawn_file_actions_addchdir_np(fileActions, spawnArena.allocateFrom(cwd.toAbsolutePath().toString()));
               checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addchdir_np() failed");
            }

            int[] restrict_pid = new int[1];
            rc = LibC.posix_spawnp(restrict_pid, cmdarray[0], fileActions, MemorySegment.NULL, cmdarray, environment);
            if (rc != 0) {
               throw new IOException("Failed to spawn process, error " + rc + " invoking posix_spawnp() for command: " + cmdarray[0]);
            }

            pid = restrict_pid[0];
         }
         finally {
            LibC.posix_spawn_file_actions_destroy(fileActions);
         }
      }
      finally {
         // If we call createPipes, even if launching the process then fails, we need to ensure
         // the child side of the pipes are closed. The parent side will be closed in onExit
         closePipes();
      }
   }

   private void closePipes()
   {
      // Close the child end of the pipes in our process
      LibC.close(stdinWidow);
      LibC.close(stdoutWidow);
      LibC.close(stderrWidow);
   }
}
