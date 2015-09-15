package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class ThreadedTest
{
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
      int procCount = 50;

      for (int ii = 0; ii < threadCount; ii++) {
         MyThread mt = new MyThread(ii, procCount);
         mt.start();
         threads.add(mt);
         System.err.printf("  started thread: %d\n", ii);
      }

      for (Thread th : threads) {
         th.join(TimeUnit.SECONDS.toMillis(20));
      }

      System.err.println("Completed threadTest1()");
   }

   static class MyThread extends Thread
   {
      private int procCount;
      private int id;

      public MyThread(int id, int procCount)
      {
         this.id = id;
         this.procCount = procCount;
      }

      public void startProcess(int PROCESSES)
      {
         String command = "/bin/cat";

         long start = System.currentTimeMillis();
         long maxFreeMem = 0;

         NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList(command));

         for (int times = 0; times < 10; times++) {
            NuProcess[] processes = new NuProcess[PROCESSES];
            LottaProcessHandler[] handlers = new LottaProcessHandler[PROCESSES];

            for (int i = 0; i < PROCESSES; i++) {
               handlers[i] = new LottaProcessHandler();
               pb.setProcessListener(handlers[i]);
               processes[i] = pb.start();
               // System.err.printf("  thread %d starting process %d: %s\n", id, i + 1, processes[i].toString());
            }

            // Kick all of the processes to start going
            for (NuProcess process : processes) {
               // System.err.printf("  Thread %d calling wantWrite() on process: %s\n", id, process.toString());
               process.wantWrite();
            }

            for (NuProcess process : processes) {
               try {
                  maxFreeMem = Math.max(maxFreeMem, Runtime.getRuntime().freeMemory());
                  process.waitFor(90, TimeUnit.SECONDS);
               }
               catch (InterruptedException ex) {
                  ex.printStackTrace();
               }
            }

            for (LottaProcessHandler handler : handlers) {
               if (handler.getAdler() != 4237270634l) {
                  System.err.println("Adler32 mismatch between written and read");
                  System.exit(-1);
               }
               else if (handler.getExitCode() != 0) {
                  System.err.println("Exit code not zero (0)");
               }
            }
         }

         System.out.printf(this.id + " Maximum memory used: %d\n", Runtime.getRuntime().totalMemory() - maxFreeMem);
         System.out.printf(this.id + " Total execution time (ms): %d\n", (System.currentTimeMillis() - start));
      }

      @Override
      public void run()
      {
         this.startProcess(this.procCount);
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
      private int exitCode;

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
      public void onExit(int statusCode)
      {
         exitCode = statusCode;
      }

      @Override
      public void onStdout(ByteBuffer buffer, boolean closed)
      {
         size += buffer.remaining();

         byte[] bytes = new byte[buffer.remaining()];
         buffer.get(bytes);
         readAdler32.update(bytes);

         if (size == LIMIT)
            nuProcess.closeStdin(true);
      }

      @Override
      public boolean onStdinReady(ByteBuffer buffer)
      {
         buffer.put(bytes);
         buffer.flip();
         return (++writes < WRITES);
      }

      int getExitCode()
      {
         return exitCode;
      }

      long getAdler()
      {
         return readAdler32.getValue();
      }
   }
}
