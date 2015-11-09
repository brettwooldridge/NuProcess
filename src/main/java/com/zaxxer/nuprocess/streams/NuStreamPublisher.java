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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.streams.NuStreamProcessBuilder.BridgeProcessHandler;

public class NuStreamPublisher implements Publisher<ByteBuffer>
{
   private static final Logger LOGGER = LoggerFactory.getLogger(NuStreamPublisher.class);

   final BridgeProcessHandler processHandler;

   private Subscriber<? super ByteBuffer> subscriber;
   private Stream stream;

   NuStreamPublisher(final BridgeProcessHandler processHandler, final Stream stream)
   {
      this.processHandler = processHandler;
      this.stream = stream;
   }

   @Override
   public void subscribe(final Subscriber<? super ByteBuffer> subscriber)
   {
      if (subscriber == null) {
         throw new NullPointerException("Subscriber cannot be null");
      }

      this.subscriber = subscriber;
      processHandler.setSubscriber(stream, subscriber);

      NuStreamSubscription subscription = new NuStreamSubscription();
      subscriber.onSubscribe(subscription);
   }

   class NuStreamSubscription implements Subscription
   {
      @Override
      public void cancel()
      {
         LOGGER.debug("NuStreamSubscription.cancel() called on subscription {}", this);
         if (stream != null) {
            final Stream s = stream;
            stream = null;
            processHandler.cancel(s);
            NuStreamPublisher.this.subscriber = null;
         }
      }

      @Override
      public void request(long n)
      {
         if (n <= 0) {
            IllegalArgumentException e = new IllegalArgumentException("Subscription.request() value cannot be less than 1, rule 3.9");
            subscriber.onError(e);
         }

         if (stream != null) {
            LOGGER.debug("NuStreamSubscription.request({}) called on subscription {}", n, this);
            processHandler.request(stream, n);
         }
      }
   }
}
