/*
 * Copyright (C) 2015 Ben Hamilton
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

package com.zaxxer.nuprocess.internal;

import com.sun.jna.JNIEnv;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

import java.util.HashMap;
import java.util.Map;

import static com.zaxxer.nuprocess.internal.Constants.OS;

public class LibJava8
{
   static {
      Map<String, Object> options = new HashMap<>();
      options.put(Library.OPTION_ALLOW_OBJECTS, Boolean.TRUE);

      if (OS != Constants.OperatingSystem.MAC) {
         Native.register(NativeLibrary.getInstance("java", options));
      }
      else {
         Native.register(NativeLibrary.getProcess(options));
      }
   }

   public static native void Java_java_lang_UNIXProcess_init(JNIEnv jniEnv, Object clazz);

   /**
    * JNIEXPORT jint JNICALL
    * Java_java_lang_UNIXProcess_forkAndExec(JNIEnv *env,
    *                                        jobject process,
    *                                        jint mode,
    *                                        jbyteArray helperpath,
    *                                        jbyteArray prog,
    *                                        jbyteArray argBlock, jint argc,
    *                                        jbyteArray envBlock, jint envc,
    *                                        jbyteArray dir,
    *                                        jintArray std_fds,
    *                                        jboolean redirectErrorStream)
    *
    * @return the PID of the process
    */
   public static native int Java_java_lang_UNIXProcess_forkAndExec(
           JNIEnv jniEnv,
           Object process,
           int mode,
           Object helperpath,
           Object prog,
           Object argBlock, int argc,
           Object envBlock, int envc,
           Object dir,
           Object fds,
           byte redirectErrorStream
   );
}
