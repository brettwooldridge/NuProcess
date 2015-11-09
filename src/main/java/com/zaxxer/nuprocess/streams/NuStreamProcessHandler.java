/*
 * Copyright (C) 201 Brett Wooldridge
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

public interface NuStreamProcessHandler
{
   /**
    * This method is invoked when you call the {@link NuStreamProcessBuilder#start()}
    * method. This is an opportunity to store away the {@code NuStreamProcess}
    * instance, possibly in your listener.
    * <p>
    * Unlike the {@link #onStart(NuStreamProcess)} method, this method is invoked
    * before the process is spawned, and is guaranteed to be invoked before any
    * other methods are called.
    * 
    * @param nuStreamProcess The {@link NuStreamProcess} that is starting. Note that
    *        the instance is not yet initialized, so it is not legal to call any of
    *        its methods, and doing so will result in undefined behavior. If you
    *        need to call any of the instance's methods, use 
    *        {@link #onStart(NuStreamProcess)} instead.
    */
   void onPreStart(NuStreamProcess nuStreamProcess);

   void onStart(NuStreamProcess nuStreamProcess);
}
