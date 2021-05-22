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

import com.sun.jna.JNIEnv;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.IEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

import static com.zaxxer.nuprocess.internal.Constants.JVM_MAJOR_VERSION;

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

   @SuppressWarnings("unused")
   private enum LaunchMechanism {
      // order IS important!
      FORK,
      POSIX_SPAWN,
      VFORK
   }

   LinuxProcess(NuProcessHandler processListener) {
      super(processListener);
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

         registerProcess();

         callStart();
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

   private void prepareProcess(List<String> command, String[] environment, Path cwd) throws IOException
   {
      String[] cmdarray = command.toArray(new String[0]);

      // See https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/classes/java/lang/ProcessImpl.java#L71-L83
      byte[][] args = new byte[cmdarray.length - 1][];
      int size = args.length; // For added NUL bytes
      for (int i = 0; i < args.length; i++) {
         args[i] = cmdarray[i + 1].getBytes();
         size += args[i].length;
      }
      byte[] argBlock = new byte[size];
      int i = 0;
      for (byte[] arg : args) {
         System.arraycopy(arg, 0, argBlock, i, arg.length);
         i += arg.length + 1;
         // No need to write NUL bytes explicitly
      }

      // See https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/classes/java/lang/ProcessImpl.java#L86
      byte[] envBlock = toEnvironmentBlock(environment);

      createPipes();
      try {
         // createPipes() returns the parent ends of the pipes, but forkAndExec requires the child ends
         int[] child_fds = {stdinWidow, stdoutWidow, stderrWidow};

         if (JVM_MAJOR_VERSION >= 10) {
            pid = com.zaxxer.nuprocess.internal.LibJava10.Java_java_lang_ProcessImpl_forkAndExec(
                    JNIEnv.CURRENT,
                    this,
                    LaunchMechanism.VFORK.ordinal() + 1,
                    toCString(System.getProperty("java.home") + "/lib/jspawnhelper"), // used on Linux
                    toCString(cmdarray[0]),
                    argBlock, args.length,
                    envBlock, environment.length,
                    (cwd != null ? toCString(cwd.toString()) : null),
                    child_fds,
                    (byte) 0 /*redirectErrorStream*/);
         }
         else {
            // See https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/classes/java/lang/UNIXProcess.java#L247
            // Native source code: https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/native/java/lang/UNIXProcess_md.c#L566
            pid = com.zaxxer.nuprocess.internal.LibJava8.Java_java_lang_UNIXProcess_forkAndExec(
                    JNIEnv.CURRENT,
                    this,
                    LaunchMechanism.VFORK.ordinal() + 1,
                    toCString(System.getProperty("java.home") + "/lib/jspawnhelper"), // used on Linux
                    toCString(cmdarray[0]),
                    argBlock, args.length,
                    envBlock, environment.length,
                    (cwd != null ? toCString(cwd.toString()) : null),
                    child_fds,
                    (byte) 0 /*redirectErrorStream*/);
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

   private static byte[] toCString(String s) {
      if (s == null)
         return null;
      byte[] bytes = s.getBytes();
      byte[] result = new byte[bytes.length + 1];
      System.arraycopy(bytes, 0,
            result, 0,
            bytes.length);
      result[result.length-1] = (byte)0;
      return result;
   }

   private static byte[] toEnvironmentBlock(String[] environment) {
      int count = environment.length; // This implicitly adds an extra null byte for each entry
      for (String entry : environment) {
         count += entry.getBytes().length;
      }

      byte[] block = new byte[count];

      int i = 0;
      for (String entry : environment) {
         byte[] bytes = entry.getBytes();
         System.arraycopy(bytes, 0, block, i, bytes.length);
         i += bytes.length + 1;
         // No need to write NUL byte explicitly
         //block[i++] = (byte) '\u0000';
      }

      return block;
   }
}
