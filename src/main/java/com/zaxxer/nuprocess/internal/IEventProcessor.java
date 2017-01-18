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

package com.zaxxer.nuprocess.internal;

import java.util.concurrent.CyclicBarrier;
/**
 * This class is internal.
 *
 * @author Brett Wooldridge
 *
 * @param <T> a subclass of {@link BasePosixProcess}
 */
public interface IEventProcessor<T extends BasePosixProcess> extends Runnable
{
   int STDIN = 0;
   int STDOUT = 1;
   int STDERR = 2;

   /**
    * Check whether the processor instance is currently running, and if not set
    * it to the running state.
    *
    * @return true if the processor was already running, false otherwise
    */
   boolean checkAndSetRunning();

   /**
    * Get the CyclicBarrier that this thread should join, along with the
    * NuProcess start thread that is starting this processor. Used to cause the
    * OsxProcess to wait until the processor is up and running before returning
    * from start() to the user.
    *
    * @return the CyclicBarrier to join to ensure the processor is running
    *         before registering processes with it
    */
   CyclicBarrier getSpawnBarrier();

   /**
    * Register a process for handling by the event processor.
    *
    * @param process the process to register
    */
   void registerProcess(T process);

   /**
    * Express that the client desires to write data into the STDIN stream as
    * soon as possible.
    *
    * @param process the process that wants to write to STDIN
    */
   void queueWrite(T process);

   void queueRead(T process, T.Stream stream);

   /**
    * Close the process's STDIN pipe.
    *
    * @param process the process whose STDIN pipe should be closed
    */
   void queueCloseStdin(T process);

   /**
    * Called by the event-loop to process asynchronous I/O events.
    *
    * @return true if events were processed, false if an idle timeout occurred
    */
   boolean process();

   /**
    * Cleanly shutdown the processors and cleanup all resources.
    */
   void shutdown();
}
