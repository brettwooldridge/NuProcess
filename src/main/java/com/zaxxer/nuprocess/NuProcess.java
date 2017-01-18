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

package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * The {@link NuProcessBuilder#start()} method creates a native process and
 * returns an instance of a platform-specific implementation of
 * {@link NuProcess} that can be used to control the process and obtain
 * information about it. The {@link NuProcess} interface provides methods for
 * expressing desire to perform output to the process, waiting for the process
 * to complete, checking the status of the process, and destroying (killing) the
 * process.
 * <p>
 * The methods that create processes may not work well for special processes on
 * certain native platforms, such as native windowing processes, daemon
 * processes, Win16/DOS processes on Microsoft Windows, or shell scripts.
 * <p>
 * See the {@link NuProcessHandler} interface for asynchronous callbacks related
 * to start, exit, and input/output notifications and operations for the
 * process.
 *
 * @author Brett Wooldridge
 */
public interface NuProcess
{
   int BUFFER_CAPACITY = 65536;

   enum Stream
   {
      STDIN,
      STDOUT,
      STDERR
   }

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
    * Express a desire to read or write data from the specified stream of the
    * process.
    * <p>
    * Calling this method with {@link Stream#STDIN} will result in the
    * {@link NuProcessHandler#onStdinReady} callback method of the process handler
    * being called when space is available in the STDIN pipe.
    * <p>
    * Calling this method with either {@link Stream#STDOUT} or {@link Stream#STDERR}
    * will result in the {@link NuProcessHandler#onStdout} or {@link NuProcessHandler#onStderr} 
    * callback method of the process handler being called respectively when output
    * is available to be read.
    *
    * @param stream the {@link Stream} that the caller wants to read/write
    */
   NuProcess want(Stream stream);

   /**
    * Performs a "direct write" rather than expressing a desire to write using
    * {@link #want(Stream)} and performing the write in a callback. Be careful
    * mixing this paradigm with the asynchronous paradigm imposed by
    * {@link #want(Stream)}.
    * <p>
    * This method returns immediately and the write of the data occurs on the
    * asynchronous processing thread. You can perform multiple
    * {@code writeStdin()} calls without regard to the size of the interprocess
    * pipe; the writes will be queued and written asynchronously as space is
    * available in the pipe.
    * <p>
    * Note that if the client process is not pulling data out of the pipe,
    * calling this method repeatedly will result is the accumulation of
    * unwritten ByteBuffers in the Java process and possibly an eventual out of
    * memory condition.
    * <p>
    * Using a Direct ByteBuffer will provide performance improvements. Note that
    * NuProcess will not flip the buffer for you; after writing your data into
    * the {@code buffer} you must flip the buffer before returning from this
    * method.
    *
    * @param buffer the {@link ByteBuffer} to write to the STDIN stream of the
    *        process
    */
   NuProcess writeStdin(ByteBuffer buffer);

   /**
    * This method is used to close the STDIN pipe between the Java process and
    * the spawned process.
    * <p>
    * If {@code force} is {@code true}, the STDIN pipe is immediately closed regardless of
    * pending unwritten data, and even data that has been written into the pipe
    * will be immediately discarded.
    * <p>
    * Otherwise, STDIN will be closed only after all pending writes
    * have completed.
    *
    * @param force {@code true} to force the pipe closed immediately
    */
   void closeStdin(boolean force);

   /**
    * This method is most useful in combination with {@link #writeStdin}. This
    * method returns true if there is outstanding data to be written to the
    * stdin stream, and false if there are no pending writes.
    *
    * @return <code>true</code> if there are pending writes or STDIN is pending
    *         close, <code>false</code> otherwise
    */
   boolean hasPendingWrites();

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

   /**
    * Sets a new process handler for this {@link NuProcess} instance. This
    * method is only safe to call from within one of the callback methods of the
    * existing {@link NuProcessHandler}.
    *
    * @param processHandler the new {@link NuProcessHandler}
    */
   NuProcess setProcessHandler(NuProcessHandler processHandler);
   
   int getPID();
}
