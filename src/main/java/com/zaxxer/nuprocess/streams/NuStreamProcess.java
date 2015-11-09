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

import com.zaxxer.nuprocess.NuProcessHandler;

public interface NuStreamProcess
{
   /**
    * Waits for the process to exit in a blocking fashion. See
    * {@link NuProcessHandler#onExit} for the non-blocking method process exit
    * notification and exit code retrieval. If the process is terminated by the
    * {@link #destroy(boolean)} method, the exit code is non-deterministic.
    * 
    * @param timeout a timeout value, 0 indicates an infinite wait
    * @param timeUnit the unit of time indicator for the timeout value
    * @return the exit code of the process, or {@code Integer.MIN_VALUE} if the
    *         timeout is reached
    * @throws InterruptedException thrown if the thread is interrupted while
    *         waiting
    */
   int waitFor(long timeout, TimeUnit timeUnit) throws InterruptedException;

   /**
    * Terminates the process.<br>
    * <br>
    * If {@code force} is {@code false}, the process will be terminated
    * gracefully (i.e. its shutdown logic will be allowed to execute), assuming
    * the OS supports such behavior. Note that the process may not actually
    * terminate, as its cleanup logic may fail or it may choose to ignore the
    * termination request. If a guarantee of termination is required, call this
    * method with {@code force} equal to {@code true} instead.
    * <p>
    * You can also call {@link #waitFor(long, TimeUnit)} after calling this
    * method with {@code force=false}, to give the process an opportunity to
    * terminate gracefully. If the timeout expires, you can then call this
    * method again with {@code force=true} to ensure the process is terminated.
    * <p>
    * If {@code force} is true, the process is guaranteed to terminate, but
    * whether it is terminated gracefully or not is OS-dependent. Note that it
    * may take the OS a moment to terminate the process, so {@link #isRunning()}
    * may return true for a brief period after calling this method. You can use
    * {@link #waitFor(long, TimeUnit)} if you want to wait until the process has
    * actually been terminated.<br>
    * <br>
    * When this method is called with <i>force</i>, the exit code returned by
    * {@link #waitFor} or passed to the {@link NuProcessHandler#onExit} callback
    * method is non-deterministic.
    *
    * @param force if <code>true</code> is passed, the process will be forcibly
    *        killed
    */
   void destroy(boolean force);

   /**
    * Tests whether or not the process is still running or has exited.
    *
    * @return true if the process is still running, false if it has exited
    */
   boolean isRunning();

   NuStreamPublisher getStdinPublisher();

   NuStreamPublisher getStdoutPublisher();

   NuStreamPublisher getStderrPublisher();
}
