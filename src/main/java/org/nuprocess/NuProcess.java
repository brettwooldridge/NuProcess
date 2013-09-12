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

package org.nuprocess;

import java.util.concurrent.TimeUnit;


/**
 * The {@link NuProcessBuilder#start()} method creates a native process and returns an instance of a platform-specific
 * implementation of NuProcess that can be used to control the process and obtain information about it. The NuProcess
 * interface provides methods for expressing desire to perform output to the process, waiting for the process to complete,
 * checking the status of the process, and destroying (killing) the process.
 * <p>
 * The methods that create processes may not work well for special processes on certain native platforms, such as native
 * windowing processes, daemon processes, Win16/DOS processes on Microsoft Windows, or shell scripts.
 * <p>
 * See the {@link NuProcessHandler} interface for asynchronous callbacks related to start, exit, and input/output
 * notifications and operations for the process.
 *
 * @author Brett Wooldridge
 */
public interface NuProcess
{
    int BUFFER_CAPACITY = 65536;

    /**
     * Waits for the process to exit in a blocking fashion.  See {@link NuProcessHandler#onExit}
     * for the non-blocking method process exit notification and exit code retrieval.  If the
     * process is terminated by the {@link #destroy()} method, the exit code is non-deterministic.
     *  
     * @param timeout a timeout value, 0 indicates an infinite wait
     * @param timeUnit the unit of time indicator for the timeout value
     * @return the exit code of the process
     * @throws InterruptedException thrown if the thread is interrupted while waiting
     */
    int waitFor(long timeout, TimeUnit timeUnit) throws InterruptedException;

    /**
     * Express a desire to write data to the STDIN stream of the process.  Calling this method
     * will result in the {@link NuProcessHandler#onStdinReady} callback method of the process
     * handler being called when space is available in the STDIN pipe.
     * <p>
     * This method will throw a {@link IllegalStateException} if the {@link #stdinClose} method
     * has already been called. 
     */
    void wantWrite();

    /**
     * This method is used to close the STDIN pipe between the Java process and the spawned
     * process.  The STDIN pipe is immediately closed regardless of pending unwritten data,
     * and even data that has been written into the pipe will be immediately discarded.
     */
    void closeStdin();
   
    /**
     * Forcefully destroy the process.  When this method is called, the exit code returned by
     * {@link #waitFor} or passed to the {@link NuProcessHandler#onExit} callback method is
     * non-deterministic.  
     */
    void destroy();

    // boolean isRunning()
}
