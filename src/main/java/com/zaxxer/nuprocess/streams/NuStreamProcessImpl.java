/*
 * Copyright (C) 2015 Brett Wooldridge
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

package com.zaxxer.nuprocess.streams;

import java.util.concurrent.TimeUnit;

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.streams.NuStreamProcessBuilder.BridgeProcessHandler;

public class NuStreamProcessImpl implements NuStreamProcess
{
   private NuStreamPublisher stdinPublisher;
   private NuStreamPublisher stdoutPublisher;
   private NuStreamPublisher stderrPublisher;
   private NuProcess nuProcess;

   NuStreamProcessImpl()
   {
   }

   void setStreamProcessHandler(final BridgeProcessHandler processHandler)
   {
      stdinPublisher = new NuStreamPublisher(processHandler, NuProcess.Stream.STDIN);
      stdoutPublisher = new NuStreamPublisher(processHandler, NuProcess.Stream.STDOUT);
      stderrPublisher = new NuStreamPublisher(processHandler, NuProcess.Stream.STDERR);
   }

   @Override
   public NuStreamPublisher getStdinPublisher()
   {
      return stdinPublisher;
   }

   @Override
   public NuStreamPublisher getStdoutPublisher()
   {
      return stdoutPublisher;
   }

   @Override
   public NuStreamPublisher getStderrPublisher()
   {
      return stderrPublisher;
   }

   @Override
   public int waitFor(long timeout, TimeUnit timeUnit) throws InterruptedException
   {
      return nuProcess.waitFor(timeout, timeUnit);
   }

   @Override
   public void destroy(boolean force)
   {
      nuProcess.destroy(force);
   }

   @Override
   public boolean isRunning()
   {
      return nuProcess.isRunning();
   }

   void setNuProcess(NuProcess nuProcess)
   {
      this.nuProcess = nuProcess;
   }
}
