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
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.internal.BasePosixProcess;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.internal.ReferenceCountedFileDescriptor;

import java.nio.file.Path;
import java.util.List;

import static com.zaxxer.nuprocess.internal.LibC.*;
import static com.zaxxer.nuprocess.internal.LinuxLibC.Java_java_lang_UNIXProcess_forkAndExec;
import static com.zaxxer.nuprocess.internal.LinuxLibC.Java_java_lang_UNIXProcess_init;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
   static {
      LibEpoll.sigignore(LibEpoll.SIGPIPE);

      Java_java_lang_UNIXProcess_init(JNIEnv.CURRENT, LinuxProcess.class);

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

      // See https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/classes/java/lang/ProcessImpl.java#L96
      int[] std_fds = new int[] { -1, -1, -1 };

      try {
         // See https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/classes/java/lang/UNIXProcess.java#L247
         // Native source code: https://github.com/JetBrains/jdk8u_jdk/blob/master/src/solaris/native/java/lang/UNIXProcess_md.c#L566
         pid = Java_java_lang_UNIXProcess_forkAndExec(
                 JNIEnv.CURRENT,
                 null,
                 LaunchMechanism.VFORK.ordinal() + 1,
                 toCString(System.getProperty("java.home") + "/lib/jspawnhelper"), // used on Linux
                 toCString(cmdarray[0]),
                 argBlock, args.length,
                 envBlock, environment.length,
                 (cwd != null ? toCString(cwd.toString()) : null),
                 std_fds,
                 (byte) 0 /*redirectErrorStream*/);

         initializeBuffers();

         if (pid == -1 || !checkLaunch()) {
            return null;
         }

         stdin = new ReferenceCountedFileDescriptor(std_fds[0]);
         stdout = new ReferenceCountedFileDescriptor(std_fds[1]);
         stderr = new ReferenceCountedFileDescriptor(std_fds[2]);

         int[] in = {-1, std_fds[0]};
         int[] out = {std_fds[1], -1};
         int[] err = {std_fds[2], -1};

         setNonBlocking(in, out, err);

         afterStart();

         registerProcess();

         callStart();

         signalProcessContinue();
      }
      catch (RuntimeException re) {
         // TODO remove from event processor pid map?
         re.printStackTrace(System.err);
         onExit(Integer.MIN_VALUE);
         return null;
      }

      return this;
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

   @Override
   protected short getSpawnFlags() {
      throw new RuntimeException("This method should not be invoked on Linux");
   }

   @Override
   protected int spawnWithCwd(IntByReference a, String b, Pointer c, Pointer d, StringArray e, Pointer f, Path g) {
      throw new RuntimeException("This method should not be invoked on Linux");
   }

   @Override
   protected Pointer createPosixSpawnFileActions() {
      throw new RuntimeException("This method should not be invoked on Linux");
   }

   @Override
   protected Pointer createPosixSpawnAttributes() {
      throw new RuntimeException("This method should not be invoked on Linux");
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
      int count = environment.length;
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
