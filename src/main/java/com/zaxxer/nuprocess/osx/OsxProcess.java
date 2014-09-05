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

import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.LibC;

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
}
