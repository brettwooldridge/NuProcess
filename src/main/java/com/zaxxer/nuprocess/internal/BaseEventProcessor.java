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

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
public abstract class BaseEventProcessor<T extends BasePosixProcess> implements IEventProcessor<T>
{
   protected static final int LINGER_TIME_MS;
   protected static final int DEADPOOL_POLL_INTERVAL;
   protected static final int LINGER_ITERATIONS;
   private final int lingerIterations;

   protected Map<Integer, T> pidToProcessMap;
   protected Map<Integer, T> fildesToProcessMap;

   protected volatile boolean shutdown;

   private CyclicBarrier startBarrier;
   private AtomicBoolean isRunning;

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
         startBarrier.await();

         int idleCount = 0;
         while (!isRunning.compareAndSet(idleCount > lingerIterations && pidToProcessMap.isEmpty(), false)) {
            idleCount = (!shutdown && process()) ? 0 : (idleCount + 1);
         }
      }
      catch (Exception e) {
         // TODO: how to handle this error?
         isRunning.set(false);
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
   }
}
