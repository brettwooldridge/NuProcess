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

import static com.zaxxer.nuprocess.internal.LibC.WEXITSTATUS;
import static com.zaxxer.nuprocess.internal.LibC.WIFEXITED;
import static com.zaxxer.nuprocess.internal.LibC.WIFSIGNALED;
import static com.zaxxer.nuprocess.internal.LibC.WTERMSIG;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.LibC;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
   private static final boolean LINUX_USE_VFORK = Boolean.parseBoolean(System.getProperty("com.zaxxer.nuprocess.linuxUseVfork", "true"));

   private static ExecutorService linuxCwdExecutorService;

   static {
      LibEpoll.sigignore(LibEpoll.SIGPIPE);

      // TODO: install signal handler for SIGCHLD, and call onExit() when received, call the default (JVM) hook if the PID is not ours

      for (int i = 0; i < processors.length; i++) {
         processors[i] = new ProcessEpoll();
      }

      ThreadPoolExecutor executor = new ThreadPoolExecutor(/* corePoolSize */ processors.length, /* maximumPoolSize */ processors.length,
                                                           /* keepAliveTime */ BaseEventProcessor.LINGER_TIME_MS, TimeUnit.MILLISECONDS,
                                                           /* workQueue */ new LinkedBlockingQueue<Runnable>(), /* threadFactory */ new LinuxCwdThreadFactory(),
                                                           /* handler */ new ThreadPoolExecutor.DiscardPolicy());
      // Allow going back down to 0 threads after LINGER_TIME_MS.
      executor.allowCoreThreadTimeOut(true);
      linuxCwdExecutorService = executor;
   }

   public LinuxProcess(NuProcessHandler processListener) {
      super(processListener);
   }

   public NuProcess start(List<String> command, String[] environment, Path cwd)
   {
      callPreStart();

      String[] commands = command.toArray(new String[command.size()]);

      Pointer posix_spawn_file_actions = createPipes();

      Pointer posix_spawnattr;
      long peer = Native.malloc(340);
      posix_spawnattr = new Pointer(peer);

      try {
         int rc = LibC.posix_spawnattr_init(posix_spawnattr);
         checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

         short flags = 0;
         if (LINUX_USE_VFORK) {
            flags = 0x40; // POSIX_SPAWN_USEVFORK
         }

         LibC.posix_spawnattr_setflags(posix_spawnattr, flags);

         IntByReference restrict_pid = new IntByReference();
         StringArray commandsArray = new StringArray(commands);
         StringArray environmentArray = new StringArray(environment);
         if (cwd != null) {
            rc = spawnLinuxWithCwd(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray, cwd);
         }
         else {
            rc = LibC.posix_spawnp(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray);
         }

         pid = restrict_pid.getValue();

         initializeBuffers();

         // This is necessary on Linux because spawn failures are not reflected in the rc, and this will reap
         // any zombies due to launch failure
         IntByReference ret = new IntByReference();
         int waitpidRc = LibC.waitpid(pid, ret, LibC.WNOHANG);
         int status = ret.getValue();
         boolean cleanExit = waitpidRc == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0;

         if (cleanExit) {
            // If the process already exited cleanly, make sure we run epoll to dispatch any stdout/stderr sent
            // before we tear everything down.
            cleanlyExitedBeforeProcess.set(true);
         }
         else if (waitpidRc != 0) {
            if (WIFEXITED(status)) {
               status = WEXITSTATUS(status);
               if (status == 127) {
                  onExit(Integer.MIN_VALUE);
               }
               else {
                  onExit(status);
               }
            }
            else if (WIFSIGNALED(status)) {
               onExit(WTERMSIG(status));
            }

            return null;
         }

         checkReturnCode(rc, "Invocation of posix_spawn() failed");

         afterStart();

         registerProcess();

         callStart();
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

         Native.free(Pointer.nativeValue(posix_spawn_file_actions));
         Native.free(Pointer.nativeValue(posix_spawnattr));
      }

      return this;
   }

   @Override
   protected Pointer createPosixSpawnFileActions()
   {
      long peer = Native.malloc(80);
      Pointer posix_spawn_file_actions = new Pointer(peer);

      return posix_spawn_file_actions;
   }

   private int spawnLinuxWithCwd(final IntByReference restrict_pid, final String restrict_path, final Pointer file_actions,
                                 final Pointer /*const posix_spawnattr_t*/ restrict_attrp, final StringArray /*String[]*/ argv, final Pointer /*String[]*/ envp,
                                 final Path cwd)
   {
      Future<Integer> setCwdThenSpawnFuture = linuxCwdExecutorService.submit(new Callable<Integer>() {
         @Override
         public Integer call()
         {
            // Set cwd in this thread, which has its cwd state disassociated from the rest of the process.
            int rc = LibC.chdir(cwd.toAbsolutePath().toString());
            // If this throws, it'll be wrapped in an ExecutionException and re-thrown on the thread
            // which calls Future.get().
            checkReturnCode(rc, "chdir() failed");
            // posix_spawnp() will inherit the cwd of this thread.
            //
            // We don't bother restoring cwd, since this thread will either be destroyed or re-used
            // later by the same executor, which will then chdir anyway.
            return LibC.posix_spawnp(restrict_pid, restrict_path, file_actions, restrict_attrp, argv, envp);
         }
      });
      try {
         return setCwdThenSpawnFuture.get();
      }
      catch (ExecutionException e) {
         Throwable cause = e.getCause();
         if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
         }
         else {
            throw new RuntimeException(cause);
         }
      }
      catch (InterruptedException e) {
         throw new RuntimeException(e);
      }
   }
}
