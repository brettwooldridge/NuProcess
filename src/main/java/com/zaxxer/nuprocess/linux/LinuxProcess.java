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

import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;

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

   public LinuxProcess(NuProcessHandler processListener) {
      super(processListener);
   }
}
