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

import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.LibC;

import static com.zaxxer.nuprocess.internal.LibC.*;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
   static {
      LibEpoll.sigignore(LibEpoll.SIGPIPE);

      // TODO: install signal handler for SIGCHLD, and call onExit() when received, call the default (JVM) hook if the PID is not ours

      for (int i = 0; i < processors.length; i++) {
         processors[i] = new ProcessEpoll();
      }
   }

   LinuxProcess(NuProcessHandler processListener) {
      super(processListener);
   }

   @Override
   protected boolean checkLaunch()
   {
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

         return false;
      }

      return true;
   }
}
