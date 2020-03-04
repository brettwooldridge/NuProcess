/*
 * Copyright (C) 2019 Brett Wooldridge
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

import com.zaxxer.nuprocess.codec.NuAbstractCharsetHandler;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

/**
 * Performs <i>synchronous</i> tests using {@link NuProcessBuilder#run}.
 */
public class RunTest
{
   private String command;

   @Rule
   public final TemporaryFolder tmp = new TemporaryFolder();

   @Before
   public void setup()
   {
      command = "cat";
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
         command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
      }
   }

   @Test
   public void lotOfProcesses() throws InterruptedException
   {
      int cpus = Runtime.getRuntime().availableProcessors();
      Assume.assumeTrue("Skipping lotOfProcesses(); the system only has 1 CPU", cpus > 1);

      // Use at least 2 threads, to ensure concurrency, but use no more than 4 threads to avoid
      // producing excessive system load
      final int threadCount = Math.max(2, Math.min(4, cpus / 2));
      System.err.println("Starting test lotOfProcesses() with " + threadCount + " threads");

      // Start threadCount threads, each running several synchronous processes in a row. This parallel
      // execution is intended to verify synchronous pumping on concurrent threads doesn't produce any
      // unexpected interactions "between" the threads
      final Thread[] threads = new Thread[threadCount];
      final AssertionError[] failures = new AssertionError[threadCount];
      for (int i = 0; i < threadCount; i++) {
         final int threadId = i + 1;
         Thread thread = new Thread("RunTest-lotOfProcesses-" + threadId) {
            @Override
            public void run()
            {
               for (int times = 0; times < 20; times++) {
                  System.err.printf("Thread %d: Iteration %d\n", threadId, times + 1);

                  LottaProcessListener listener = new LottaProcessListener();
                  NuProcessBuilder pb = new NuProcessBuilder(listener, command);
                  pb.run();

                  try {
                     Assert.assertTrue("Adler32 mismatch between written and read", listener.checkAdlers());
                     Assert.assertEquals("Exit code mismatch", 0, listener.getExitCode());
                  } catch (AssertionError e) {
                     failures[threadId - 1] = e;
                     break;
                  }
               }
            }
         };
         thread.setDaemon(true);
         thread.start();

         threads[i] = thread;
      }

      // After all the threads are started, wait for each to finish and then check to see whether it
      // inserted an AssertionError into the failures array
      int failed = 0;
      for (int i = 0; i < threadCount; i++) {
         threads[i].join();
         if (failures[i] != null) {
            System.err.printf("Thread %d failed: %s", threadCount + 1, failures[i].getMessage());
            ++failed;
         }
      }

      // If any threads failed, the test failed
      if (failed > 0) {
         Assert.fail(failed + " thread(s) failed");
      }

      System.err.println("Completed test lotOfProcesses()");
   }

   @Test
   public void lotOfData()
   {
      System.err.println("Starting test lotOfData()");
      for (int i = 0; i < 100; i++) {
         LottaProcessListener processListener = new LottaProcessListener();
         NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
         pb.run();

         Assert.assertTrue("Adler32 mismatch between written and read", processListener.checkAdlers());
      }

      System.err.println("Completed test lotOfData()");
   }

   @Test
   public void decodingShortUtf8Data()
   {
      String SHORT_UNICODE_TEXT = "Hello \uD83D\uDCA9 world";
      System.err.println("Starting test decodingShortUtf8Data()");
      Utf8DecodingListener processListener = new Utf8DecodingListener(SHORT_UNICODE_TEXT, true);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      pb.run();
      Assert.assertEquals("Decoding mismatch", SHORT_UNICODE_TEXT, processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      Assert.assertFalse("Decoder stdin should not overflow", processListener.stdinOverflow);
      System.err.println("Completed test decodingShortUtf8Data()");
   }

   @Test
   public void decodingLongUtf8Data()
   {
      // We use 3 bytes to make sure at least one UTF-8 boundary goes across two byte buffers.
      String THREE_BYTE_UTF_8 = "\u2764";
      StringBuilder unicodeTextWhichDoesNotFitInBuffer = new StringBuilder();
      for (int i = 0; i < NuProcess.BUFFER_CAPACITY + 1; i++) {
         unicodeTextWhichDoesNotFitInBuffer.append(THREE_BYTE_UTF_8);
      }
      System.err.println("Starting test decodingLongUtf8Data()");
      Utf8DecodingListener processListener = new Utf8DecodingListener(unicodeTextWhichDoesNotFitInBuffer.toString(), true);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      pb.run();
      Assert.assertEquals("Decoding mismatch", unicodeTextWhichDoesNotFitInBuffer.toString(), processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      Assert.assertTrue("Decoder stdin should overflow", processListener.stdinOverflow);
      System.err.println("Completed test decodingLongUtf8Data()");
   }

   @Test
   public void badExit() throws InterruptedException
   {
      System.err.println("Starting test badExit()");

      final AtomicInteger asyncExitCode = new AtomicInteger();
      final CountDownLatch exitLatch = new CountDownLatch(1);

      NuProcessHandler processListener = new NuAbstractProcessHandler() {
         @Override
         public void onExit(int statusCode)
         {
            asyncExitCode.set(statusCode);
            exitLatch.countDown();
         }
      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, command, "/tmp/sdfadsf");
      NuProcess nuProcess = pb.start();
      int syncExitCode = nuProcess.waitFor(5, TimeUnit.SECONDS);
      boolean countedDown = exitLatch.await(5, TimeUnit.SECONDS);
      Assert.assertTrue("Async exit latch was not triggered", countedDown);

      int expectedExitCode = System.getProperty("os.name").toLowerCase().contains("win") ? -1 : 1;
      Assert.assertEquals("Exit code (synchronous) did not match expectation", expectedExitCode, syncExitCode);
      Assert.assertEquals("Exit code (asynchronous) did not match expectation", expectedExitCode, asyncExitCode.get());

      System.err.println("Completed test badExit()");
   }

   @Test
   public void noExecutableFound()
   {
      System.err.println("Starting test noExecutableFound()");

      final Semaphore semaphore = new Semaphore(0);
      final AtomicInteger exitCode = new AtomicInteger();

      NuProcessHandler processListener = new NuAbstractProcessHandler() {
         @Override
         public void onExit(int statusCode)
         {
            exitCode.set(statusCode);
            semaphore.release();
         }
      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, "/bin/zxczxc");
      NuProcess process = pb.start();
      semaphore.acquireUninterruptibly();
      Assert.assertFalse("Process incorrectly reported running", process.isRunning());
      Assert.assertEquals("Output did not matched expected result", Integer.MIN_VALUE, exitCode.get());

      System.err.println("Completed test noExecutableFound()");
   }

   @Test
   public void callbackOrder() throws InterruptedException
   {
      final List<String> callbacks = new CopyOnWriteArrayList<>();
      final CountDownLatch latch = new CountDownLatch(1);

      NuProcessHandler handler = new NuProcessHandler() {
         private NuProcess nuProcess;

         @Override
         public void onStdout(ByteBuffer buffer, boolean closed)
         {
            callbacks.add("stdout");
            nuProcess.closeStdin(true);
         }

         @Override
         public boolean onStdinReady(ByteBuffer buffer)
         {
            callbacks.add("stdin");
            buffer.put("foobar".getBytes()).flip();
            return false;
         }

         @Override
         public void onStderr(ByteBuffer buffer, boolean closed)
         {
            callbacks.add("stderr");
         }

         @Override
         public void onStart(NuProcess nuProcess)
         {
            callbacks.add("start");
            this.nuProcess = nuProcess;
            nuProcess.wantWrite();
         }

         @Override
         public void onPreStart(NuProcess nuProcess)
         {
            callbacks.add("prestart");
         }

         @Override
         public void onExit(int exitCode)
         {
            callbacks.add("exit");
            latch.countDown();
         }
      };

      Assert.assertNotNull("process is null", new NuProcessBuilder(handler, command).start());
      latch.await();

      Assert.assertEquals("onPreStart was not called first", 0, callbacks.indexOf("prestart"));
      Assert.assertFalse("onExit was called before onStdout", callbacks.indexOf("exit") < callbacks.lastIndexOf("stdout"));
   }

   @Test
   public void changeCwd() throws IOException
   {
      Path javaCwd = Paths.get(System.getProperty("user.dir"));
      Path tmpPath = tmp.getRoot().toPath();
      System.err.println("Starting test changeCwd() (java cwd=" + javaCwd + ", tmp=" + tmpPath + ")");
      Assert.assertNotEquals("java cwd should not be tmp path before process", javaCwd.toRealPath(), tmpPath.toRealPath());
      String message = "Hello cwd-aware world\n";
      Files.write(tmpPath.resolve("foo.txt"), message.getBytes(StandardCharsets.UTF_8));
      Utf8DecodingListener processListener = new Utf8DecodingListener("", true);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command, "foo.txt");
      pb.setCwd(tmpPath);
      pb.run();
      Assert.assertEquals("Output mismatch", message, processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      javaCwd = Paths.get(System.getProperty("user.dir"));
      Assert.assertNotEquals("java cwd should not be tmp path after process", javaCwd.toRealPath(), tmpPath.toRealPath());
      System.err.println("Completed test changeCwd()");
   }

   @Test
   public void softCloseStdinAfterWrite()
   {
      String text = "Hello world!";
      System.err.println("Starting test softCloseStdinAfterWrite()");
      Utf8DecodingListener processListener = new Utf8DecodingListener(text, false);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      pb.run();
      Assert.assertEquals("Decoding mismatch", text, processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      Assert.assertFalse("Decoder stdin should not overflow", processListener.stdinOverflow);
      System.err.println("Completed test softCloseStdinAfterWrite()");
   }

   private static byte[] getLotsOfBytes()
   {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 6000; i++) {
         sb.append("1234567890");
      }

      return sb.toString().getBytes();
   }

   private static class LottaProcessListener extends NuAbstractProcessHandler
   {
      private static final int WRITES = 10;
      private NuProcess nuProcess;
      private int writes;
      private int size;
      private int exitCode;

      private Adler32 readAdler32;
      private Adler32 writeAdler32;
      private byte[] bytes;

      LottaProcessListener()
      {
         this.readAdler32 = new Adler32();
         this.writeAdler32 = new Adler32();

         bytes = getLotsOfBytes();
         exitCode = -1;
      }

      @Override
      public void onStart(NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
         nuProcess.wantWrite();
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
         if (size == (WRITES * bytes.length)) {
            nuProcess.closeStdin(true);
         }

         byte[] bytes = new byte[buffer.remaining()];
         buffer.get(bytes);
         readAdler32.update(bytes);
      }

      @Override
      public boolean onStdinReady(ByteBuffer buffer)
      {
         writeAdler32.update(bytes);

         buffer.put(bytes);
         buffer.flip();

         return (++writes < WRITES);
      }

      int getExitCode()
      {
         return exitCode;
      }

      boolean checkAdlers()
      {
         return writes == WRITES && readAdler32.getValue() == writeAdler32.getValue();
      }
   }

   private static class Utf8DecodingListener extends NuAbstractCharsetHandler
   {
      private final CharBuffer utf8Buffer;
      private final boolean forceCloseStdin;
      private int charsWritten;
      private int charsRead;
      private NuProcess nuProcess;
      StringBuilder decodedStdout;
      boolean stdinOverflow;
      int exitCode;

      Utf8DecodingListener(String utf8Text, boolean forceCloseStdin)
      {
         super(StandardCharsets.UTF_8);
         this.utf8Buffer = CharBuffer.wrap(utf8Text);
         this.forceCloseStdin = forceCloseStdin;
         this.charsWritten = 0;
         this.charsRead = 0;
         this.decodedStdout = new StringBuilder();
         this.stdinOverflow = false;
         this.exitCode = -1;
      }

      @Override
      public void onStart(NuProcess nuProcess)
      {
         this.nuProcess = nuProcess;
         nuProcess.wantWrite();
      }

      @Override
      public void onExit(int statusCode)
      {
         exitCode = statusCode;
      }

      @Override
      public void onStdoutChars(CharBuffer buffer, boolean closed, CoderResult coderResult)
      {
         charsRead += buffer.remaining();
         decodedStdout.append(buffer);
         buffer.position(buffer.limit());

         if (forceCloseStdin && charsRead == charsWritten) {
            nuProcess.closeStdin(true);
         }
      }

      @Override
      public boolean onStdinCharsReady(CharBuffer buffer)
      {
         if (utf8Buffer.remaining() <= buffer.remaining()) {
            charsWritten += utf8Buffer.remaining();
            buffer.put(utf8Buffer);
            buffer.flip();
            if (!forceCloseStdin) {
               nuProcess.closeStdin(false);
            }
            return false;
         }
         else {
            charsWritten += buffer.remaining();
            buffer.put(utf8Buffer.subSequence(0, buffer.remaining()));
            buffer.flip();
            utf8Buffer.position(utf8Buffer.position() + buffer.remaining());
            stdinOverflow = true;
            return true;
         }
      }
   }
}
