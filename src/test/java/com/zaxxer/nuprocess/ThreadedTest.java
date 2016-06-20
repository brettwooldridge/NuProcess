package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.zaxxer.nuprocess.NuProcess.Stream;

public class ThreadedTest
{
   private ArrayList<AssertionError> errors = new ArrayList<>();

   @Before
   public void unixOnly()
   {
      Assume.assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
   }

   @Test
   public void threadTest1() throws InterruptedException
   {
      System.err.println("Starting threadTest1()");

      ArrayList<Thread> threads = new ArrayList<Thread>();
      int threadCount = 4;
      int procCount = 20;

      for (int ii = 0; ii < threadCount; ii++) {
         MyThread mt = new MyThread(ii, procCount);
         mt.start();
         threads.add(mt);
         System.err.printf("  started thread: %d\n", ii);
      }

      for (Thread th : threads) {
         th.join(TimeUnit.SECONDS.toMillis(20));
         MyThread mt = (MyThread) th;
         if (mt.failure != null) {
            Assert.fail(mt.failure);
         }
      }

      System.err.println("Completed threadTest1()");
   }

   class MyThread extends Thread
   {
      private int procCount;
      private int id;
      private String failure;

      public MyThread(int id, int procCount)
      {
         this.id = id;
         this.procCount = procCount;
      }

      public void startProcess(int PROCESSES) throws InterruptedException
      {
         String command = "/bin/cat";

         long start = System.currentTimeMillis();

         NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList(command));

         for (int times = 0; times < 10; times++) {
            NuProcess[] processes = new NuProcess[PROCESSES];
            LottaProcessHandler[] handlers = new LottaProcessHandler[PROCESSES];

            for (int i = 0; i < PROCESSES; i++) {
               handlers[i] = new LottaProcessHandler();
               pb.setProcessListener(handlers[i]);
               // System.err.printf("  thread %d starting process %d...\n", id, i + 1);
               processes[i] = pb.start();
               processes[i].want(Stream.STDOUT);
               processes[i].want(Stream.STDERR);
               // System.err.printf("  thread %d started process %d: %s\n", id, i + 1, processes[i].toString());
            }

            // Kick all of the processes to start going
            for (NuProcess process : processes) {
               // System.err.printf("  Thread %d calling wantWrite() on process: %s\n", id, process.toString());
               process.want(Stream.STDIN);
            }

            ArrayList<NuProcess> procList = new ArrayList<>(Arrays.asList(processes));
            while (!procList.isEmpty()) {
               Iterator<NuProcess> iterator = procList.iterator();
               while (iterator.hasNext()) {
                  NuProcess process = iterator.next();
                  int rc = process.waitFor(250, TimeUnit.MILLISECONDS);
                  if (rc != Integer.MIN_VALUE) {
                     // System.err.println(process + " exited with rc=" + rc);
                     
                     iterator.remove();
                  }
                  // System.err.println("Still waiting for " + process);
               }
            }

            for (LottaProcessHandler handler : handlers) {
               try {
                  Assert.assertEquals("Adler32 mismatch", 4237270634L, handler.getAdler() );
               }
               catch (AssertionError e) {
                  errors.add(e);
               }
            }
         }

         System.out.printf(this.id + " Total execution time (ms): %d\n", (System.currentTimeMillis() - start));
      }

      @Override
      public void run()
      {
         try {
            this.startProcess(this.procCount);
         }
         catch (InterruptedException e){
            e.printStackTrace();
         }
      }
   }

   private static class LottaProcessHandler extends NuAbstractProcessHandler
   {
      private static final int WRITES = 100;
      private static final int LIMIT;
      private static final byte[] bytes;

      private NuProcess nuProcess;
      private int writes;
      private int size;

      private Adler32 readAdler32 = new Adler32();

      static {
         // Create 600K of data.
         StringBuffer sb = new StringBuffer();
         for (int i = 0; i < 6000; i++) {
            sb.append("1234567890");
         }
         bytes = sb.toString().getBytes();
         LIMIT = WRITES * bytes.length;
      }

      @Override
      public void onStart(final NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
      }

      @Override
      public boolean onStdout(ByteBuffer buffer, boolean closed)
      {
         size += buffer.remaining();

         byte[] bytes = new byte[buffer.remaining()];
         buffer.get(bytes);
         readAdler32.update(bytes);

//         if (size == LIMIT) {
//            nuProcess.closeStdin(true);
//            size = Integer.MAX_VALUE;
//         }

         // System.err.println(nuProcess + " adler32: " + readAdler32.getValue());
         return !closed && (size < LIMIT);
      }

      @Override
      public boolean onStdinReady(ByteBuffer buffer)
      {
         buffer.put(bytes);
         buffer.flip();

         boolean close = (++writes >= WRITES);
         if (close) {
            nuProcess.closeStdin(false);
         }

         return !close;
      }

      long getAdler()
      {
         return readAdler32.getValue();
      }
   }
}
