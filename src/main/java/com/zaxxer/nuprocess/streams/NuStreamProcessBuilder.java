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

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.NuProcessBuilder;

public class NuStreamProcessBuilder
{
   private static final Logger LOGGER = LoggerFactory.getLogger(NuStreamProcessBuilder.class);

   private final NuProcessBuilder builder;

   public NuStreamProcessBuilder(final NuProcessBuilder builder)
   {
      this.builder = builder;
   }

   public NuStreamProcess start()
   {
      return start(null);
   }

   public NuStreamProcess start(final NuStreamProcessHandler streamProcessHandler)
   {
      NuStreamProcessImpl streamProcess = new NuStreamProcessImpl();

      BridgeProcessHandler bridgeProcessHandler = new BridgeProcessHandler(streamProcess, streamProcessHandler);
      builder.setProcessListener(bridgeProcessHandler);

      NuProcess nuProcess = builder.start();

      streamProcess.setNuProcess(nuProcess);
      streamProcess.setStreamProcessHandler(bridgeProcessHandler);

      return streamProcess;
   }

   static class BridgeProcessHandler extends NuAbstractProcessHandler
   {
      private final AtomicLong stdinRequests;
      private final AtomicLong stdoutRequests;
      private final AtomicLong stderrRequests;
      private final NuStreamProcess streamProcess;
      private final NuStreamProcessHandler streamProcessHandler;
      
      private NuProcess nuProcess;
      private volatile Subscriber<? super ByteBuffer> stdinSubscriber;
      private volatile Subscriber<? super ByteBuffer> stdoutSubscriber;
      private volatile Subscriber<? super ByteBuffer> stderrSubscriber;

      private boolean stdinComplete;
      private boolean stdoutComplete;
      private boolean stderrComplete;

      public BridgeProcessHandler(final NuStreamProcess streamProcess, final NuStreamProcessHandler streamProcessHandler)
      {
         this.streamProcessHandler = streamProcessHandler;
         this.streamProcess = streamProcess;
         this.stdinRequests = new AtomicLong();
         this.stdoutRequests = new AtomicLong();
         this.stderrRequests = new AtomicLong();
      }

      @Override
      public void onPreStart(final NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
         if (streamProcessHandler != null) {
            streamProcessHandler.onPreStart(streamProcess);
         }
      }

      @Override
      public void onStart(final NuProcess nuProcess)
      {
         if (streamProcessHandler != null) {
            streamProcessHandler.onStart(streamProcess);
         }
      }

      @Override
      public void onExit(int statusCode)
      {
         LOGGER.debug("{}.onExit() was called", this.getClass().getSimpleName());
         // TODO do we ever need to call stdinSubscriber.onError() ?
         if (stdinSubscriber != null) {
            if (!stdinComplete) {
               stdinComplete = true;
               stdinSubscriber.onComplete();
            }
            stdinRequests.set(-1);
         }

         if (stdoutSubscriber != null) {
            if (!stdoutComplete) {
               stdoutComplete = true;
               stdoutSubscriber.onComplete();
            }
            stdoutRequests.set(-1);
         }

         if (stderrSubscriber != null) {
            if (!stdoutComplete) {
               stdoutComplete = true;
               stderrSubscriber.onComplete();
            }
            stderrRequests.set(-1);
         }
      }

      @Override
      public boolean onStdinReady(final ByteBuffer buffer)
      {
         if (stdinRequests.get() < 0) {
            return false;
         }

         if (stdinSubscriber != null) {
            stdinSubscriber.onNext(buffer);
         }

         buffer.flip();
         return stdinRequests.decrementAndGet() > 0;
      }

      @Override
      public boolean onStdout(final ByteBuffer buffer, final boolean closed)
      {
         if (stdoutRequests.get() <= 0) {
            return false;
         }

         final Subscriber<? super ByteBuffer> subscriber = stdoutSubscriber;
         if (buffer.hasRemaining() && subscriber != null) {
            LOGGER.debug("{} calling {}.onNext()", this.getClass().getSimpleName(), subscriber.getClass().getSimpleName());
            subscriber.onNext(buffer);
         }

         if (closed) {
            if (subscriber != null) {
               LOGGER.debug("{} calling {}.onComplete()", this.getClass().getSimpleName(), subscriber.getClass().getSimpleName());               
               stdoutSubscriber = null;
               if (!stdoutComplete) {
                  stdoutComplete = true;
                  subscriber.onComplete();
               }
            }

            stdoutRequests.set(-1);
         }

         boolean more = !closed && stdoutRequests.decrementAndGet() > 0;
         LOGGER.debug("{} requesting more data: {}", this.getClass().getSimpleName(), more);
         
         return more;
      }
      
      @Override
      public boolean onStderr(final ByteBuffer buffer, final boolean closed)
      {
         if (stderrRequests.get() <= 0) {
            return false;
         }

         final Subscriber<? super ByteBuffer> subscriber = stderrSubscriber;
         if (buffer.hasRemaining() && subscriber != null) {
            LOGGER.debug("{} calling {}.onNext()", this.getClass().getSimpleName(), subscriber.getClass().getSimpleName());
            subscriber.onNext(buffer);
         }

         if (closed) {
            if (subscriber != null) {
               LOGGER.debug("{} calling {}.onComplete()", this.getClass().getSimpleName(), subscriber.getClass().getSimpleName());               
               stderrSubscriber = null;
               if (!stderrComplete) {
                  stderrComplete = true;
                  subscriber.onComplete();
               }
            }

            stderrRequests.set(-1);
         }

         boolean more = !closed && stderrRequests.decrementAndGet() > 0;
         LOGGER.debug("{} requesting more data: {}", this.getClass().getSimpleName(), more);

         return more;
      }

      void setSubscriber(final Stream stream, final Subscriber<? super ByteBuffer> subscriber)
      {
         switch (stream)
         {
            case STDIN:
               stdinSubscriber = subscriber;
               stdinRequests.set(0);
               break;
            case STDOUT:
               stdoutSubscriber = subscriber;
               stdoutRequests.set(0);
               break;
            case STDERR:
               stderrSubscriber = subscriber;
               stderrRequests.set(0);
               break;
         }
      }
      
      void request(Stream stream, long n)
      {
         switch (stream) {
         case STDOUT:
            if (stdoutSubscriber != null && stdoutRequests.get() >= 0 && Long.MAX_VALUE - stdoutRequests.get() >= n) {
               if (stdoutRequests.getAndAdd(n) == 0) {
                  nuProcess.want(stream);
               }
               else {
                  LOGGER.debug("NuProcess.want({}) elided for request({}) because there were already pending requests", stream, n);
               }
            }
            else {
               LOGGER.debug("request({}) ignored (subscriber={}, requests={})", n, stdoutSubscriber, stdoutRequests);
            }
            break;
         case STDIN:
            if (stdinSubscriber != null && stdinRequests.get() >= 0 && Long.MAX_VALUE - stdinRequests.get() >= n && stdinRequests.getAndAdd(n) == 0) {
               nuProcess.want(stream);
            }
            break;
         case STDERR:
            if (stderrSubscriber != null && stderrRequests.get() >= 0 && Long.MAX_VALUE - stderrRequests.get() >= n && stderrRequests.getAndAdd(n) == 0) {
               nuProcess.want(stream);
            }
            break;
         }
      }

      void cancel(Stream stream)
      {
         switch (stream) {
         case STDOUT:
            stdoutRequests.set(-1);
            stdoutSubscriber = null;
            break;
         case STDIN:
            stdinRequests.set(-1);
            stdinSubscriber = null;
            break;
         case STDERR:
            stderrRequests.set(-1);
            stderrSubscriber = null;
            break;
         }         
      }

      NuProcess getNuProcess()
      {
         return nuProcess;
      }
   }
}
