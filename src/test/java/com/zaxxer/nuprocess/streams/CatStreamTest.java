package com.zaxxer.nuprocess.streams;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;

public class CatStreamTest
{
   private String command;

   @Before
   public void setup()
   {
      command = "cat";
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
         command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
      }
   }

   @Test
   public void slowRead() throws InterruptedException, UnsupportedEncodingException
   {
      class NaiveSubscriber implements Subscriber<ByteBuffer>
      {
         private Timer timer;
         private Subscription subscription;
         private Adler32 readAdler32;

         byte[] bytes;

         NaiveSubscriber()
         {
            timer = new Timer(true);
            readAdler32 = new Adler32();
            bytes = new byte[NuProcess.BUFFER_CAPACITY];
         }

         @Override
         public void onComplete()
         {
            timer.cancel();
            System.err.printf("\nFinal Adler32: %d\n", readAdler32.getValue());
         }
         
         @Override
         public void onError(Throwable t)
         {
         }
         
         @Override
         public void onNext(final ByteBuffer buffer)
         {
            final int available = buffer.remaining();
            if (buffer.hasRemaining()) {
               buffer.get(bytes, 0, available);
               readAdler32.update(bytes, 0, available);
               // System.out.print(new String(bytes, 0, available));
               
               timer.schedule(new TimerTask() {
                  public void run()
                  {
                     subscription.request(2);
                  }
               }, TimeUnit.MILLISECONDS.toMillis(250));            
            }
            else {
               System.err.println("\nUnexpected.");
            }
         }
         
         @Override
         public void onSubscribe(final Subscription sub)
         {
            subscription = sub;
            timer.schedule(new TimerTask() {
               public void run()
               {
                  subscription.request(1);
               }
            }, 0);
         }
      }

      final NuProcessBuilder builder = new NuProcessBuilder(command, "src/test/resources/chunk.txt");
      final NuStreamProcessBuilder streamBuilder = new NuStreamProcessBuilder(builder);
      final NuStreamProcess process = streamBuilder.start();

      final NuStreamPublisher stdoutPublisher = process.getStdoutPublisher();

      final NaiveSubscriber subscriber = new NaiveSubscriber();
      stdoutPublisher.subscribe(subscriber);

      process.waitFor(0, TimeUnit.SECONDS); // wait until the process exists

      Assert.assertTrue("'end of file' was not matched", new String(subscriber.bytes, "UTF-8").contains("end of file"));
      Assert.assertEquals("Adler32 checksum not as expected", 2970784471L, subscriber.readAdler32.getValue());
   }
}
