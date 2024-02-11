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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Memory;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;

/**
 * @author Brett Wooldridge
 */
public abstract class BaseEventProcessor<T extends BasePosixProcess> implements IEventProcessor<T>
{
   public static final int LINGER_TIME_MS;

   protected static final int DEADPOOL_POLL_INTERVAL;
   protected static final int LINGER_ITERATIONS;

   private static final Logger LOGGER = Logger.getLogger(BaseEventProcessor.class.getCanonicalName());

   private final int lingerIterations;

   protected Map<Integer, T> pidToProcessMap;
   protected Map<Integer, T> fildesToProcessMap;

   protected volatile boolean shutdown;

   private CyclicBarrier startBarrier;
   private final AtomicBoolean isRunning;

   // avoid unnecessary malloc calls by reusing native pointer
   // this field is thread safe as this is only used by EventProcess thread
   protected final IntByReference tempPointer = new IntByReference();

   // out/err buffers are per epoll processor for allocation free steady state
   protected final Memory tempBufferMem = new Memory(NuProcess.BUFFER_CAPACITY);
   protected final ByteBuffer tempBuffer = tempBufferMem.getByteBuffer(0, tempBufferMem.size()).order(ByteOrder.nativeOrder());

   static {
      LINGER_TIME_MS = Math.max(1000, Integer.getInteger("com.zaxxer.nuprocess.lingerTimeMs", 2500));

      DEADPOOL_POLL_INTERVAL = Math.min(LINGER_TIME_MS, Math.max(100, Integer.getInteger("com.zaxxer.nuprocess.deadPoolPollMs", 250)));

      LINGER_ITERATIONS = LINGER_TIME_MS / DEADPOOL_POLL_INTERVAL;
   }

   public BaseEventProcessor()
   {
      this(LINGER_ITERATIONS);
   }

   public BaseEventProcessor(int lingerIterations)
   {
      this.lingerIterations = lingerIterations;
      pidToProcessMap = new ConcurrentHashMap<Integer, T>();
      fildesToProcessMap = new ConcurrentHashMap<Integer, T>();
      isRunning = new AtomicBoolean();
   }

   /**
    * The primary run loop of the event processor.
    */
   @Override
   public void run()
   {
      try {
         // If the process is running synchronously, startBarrier will be null
         if (startBarrier != null) {
            startBarrier.await();
         }

         int idleCount = 0;
         while (!isRunning.compareAndSet(idleCount > lingerIterations && pidToProcessMap.isEmpty(), false)) {
            idleCount = (!shutdown && process()) ? 0 : (idleCount + 1);
         }
      }
      catch (Exception e) {
         // TODO: how to handle this error?
         LOGGER.log(Level.WARNING, "Aborting processing loop after unexpected exception (" +
                 pidToProcessMap.size() + " processes running)", e);
         isRunning.set(false);
      }
      finally {
         if (startBarrier == null) {
            // If the process is running synchronously, when the run loop ends give the subclass
            // an opportunity to close any descriptors it might have been using
            close();
         }

         JnaHelper.free(tempPointer);
         JnaHelper.free(tempBufferMem);
      }
   }

   /** {@inheritDoc} */
   @Override
   public CyclicBarrier getSpawnBarrier()
   {
      startBarrier = new CyclicBarrier(2);
      return startBarrier;
   }

   /** {@inheritDoc} */
   @Override
   public boolean checkAndSetRunning()
   {
      return isRunning.compareAndSet(false, true);
   }

   /** {@inheritDoc} */
   @Override
   public void shutdown()
   {
      shutdown = true;
      Collection<T> processes = pidToProcessMap.values();
      IntByReference exitCode = new IntByReference();
      for (T process : processes) {
         LibC.kill(process.getPid(), LibC.SIGTERM);
         process.onExit(Integer.MAX_VALUE - 1);
         LibC.waitpid(process.getPid(), exitCode, LibC.WNOHANG);
      }
      JnaHelper.free(exitCode);
   }

   /**
    * Closes the processor, freeing up any resources (such as file descriptors) it was using.
    * <p>
    * <b>Note</b>: This method is only called for processors that are used to pump <i>synchronous</i> processes.
    * Processors used to pump <i>asynchronous</i> processes are never closed; while their threads may be stopped,
    * when there are no processes to pump, they are restarted if a new asynchronous process is started and will
    * reuse the same resources.
    *
    * @since 1.3
    */
   protected abstract void close();
}
