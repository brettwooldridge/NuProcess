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

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessFactory;
import com.zaxxer.nuprocess.NuProcessHandler;

public class OsxProcessFactory implements NuProcessFactory
{
   /** {@inheritDoc} */
   @Override
   public NuProcess createProcess(List<String> commands, String[] env, NuProcessHandler processListener, Path cwd)
   {
      OsxProcess process = new OsxProcess(processListener);
      process.start(commands, env, cwd);
      return process;
   }

   /** {@inheritDoc} */
   @Override
   public void runProcess(List<String> commands, String[] env, NuProcessHandler processListener, Path cwd)
   {
      OsxProcess process = new OsxProcess(processListener);
      process.run(commands, env, cwd);
   }
}
