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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
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

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.zaxxer.nuprocess.codec.NuAbstractCharsetHandler;

/**
 * Performs <i>asynchronous</i> tests using {@link NuProcessBuilder#start}.
 *
 * @author Brett Wooldridge
 */
// @RunWith(value=RunOnlyOnUnix.class)
public class CatTest
{
   private String command;

   @Rule
   public TemporaryFolder tmp = new TemporaryFolder();

   @Before
   public void setup()
   {
      command = "cat";
      if (System.getProperty("os.name").toLowerCase().contains("win")) {
         command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
      }
   }

   @Test
   public void lotOfProcesses() throws Exception
   {
      System.err.println("Starting test lotOfProcesses()");
      for (int times = 0; times < 20; times++) {
         System.err.printf(" Iteration %d\n", times + 1);

         Semaphore[] semaphores = new Semaphore[100];
         LottaProcessListener[] listeners = new LottaProcessListener[100];

         for (int i = 0; i < semaphores.length; i++) {
            semaphores[i] = new Semaphore(0);
            listeners[i] = new LottaProcessListener(semaphores[i]);
            NuProcessBuilder pb = new NuProcessBuilder(listeners[i], command);
            pb.start();
            // System.err.printf("  starting process: %d\n", i + 1);
         }

         for (Semaphore sem : semaphores) {
            sem.acquire();
         }

         for (LottaProcessListener listen : listeners) {
            Assert.assertTrue("Adler32 mismatch between written and read", listen.checkAdlers());
            Assert.assertEquals("Exit code mismatch", 0, listen.getExitCode());
         }
      }

      System.err.println("Completed test lotOfProcesses()");
   }

   @Test
   public void lotOfData() throws Exception
   {
      System.err.println("Starting test lotOfData()");
      for (int i = 0; i < 100; i++) {
         Semaphore semaphore = new Semaphore(0);

         LottaProcessListener processListener = new LottaProcessListener(semaphore);
         NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
         pb.start();
         semaphore.acquireUninterruptibly();

         Assert.assertTrue("Adler32 mismatch between written and read", processListener.checkAdlers());
      }

      System.err.println("Completed test lotOfData()");
   }

   @Test
   public void decodingShortUtf8Data() throws Exception
   {
      Semaphore semaphore = new Semaphore(0);
      String SHORT_UNICODE_TEXT = "Hello \uD83D\uDCA9 world";
      System.err.println("Starting test decodingShortUtf8Data()");
      Utf8DecodingListener processListener = new Utf8DecodingListener(semaphore, SHORT_UNICODE_TEXT, true);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      pb.start();
      semaphore.acquireUninterruptibly();
      Assert.assertEquals("Decoding mismatch", SHORT_UNICODE_TEXT, processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      Assert.assertFalse("Decoder stdin should not overflow", processListener.stdinOverflow);
      System.err.println("Completed test decodingShortUtf8Data()");
   }

   @Test
   public void decodingLongUtf8Data() throws Exception
   {
      Semaphore semaphore = new Semaphore(0);
      // We use 3 bytes to make sure at least one UTF-8 boundary goes across two byte buffers.
      String THREE_BYTE_UTF_8 = "\u2764";
      StringBuilder unicodeTextWhichDoesNotFitInBuffer = new StringBuilder();
      for (int i = 0; i < NuProcess.BUFFER_CAPACITY + 1; i++) {
         unicodeTextWhichDoesNotFitInBuffer.append(THREE_BYTE_UTF_8);
      }
      System.err.println("Starting test decodingLongUtf8Data()");
      Utf8DecodingListener processListener = new Utf8DecodingListener(semaphore, unicodeTextWhichDoesNotFitInBuffer.toString(), true);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      pb.start();
      semaphore.acquireUninterruptibly();
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
      final List<String> callbacks = new CopyOnWriteArrayList<String>();
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
   public void changeCwd() throws InterruptedException, IOException
   {
      Path javaCwd = Paths.get(System.getProperty("user.dir"));
      Path tmpPath = tmp.getRoot().toPath();
      System.err.println("Starting test changeCwd() (java cwd=" + javaCwd + ", tmp=" + tmpPath + ")");
      Assert.assertNotEquals("java cwd should not be tmp path before process", javaCwd.toRealPath(), tmpPath.toRealPath());
      String message = "Hello cwd-aware world\n";
      Files.write(tmpPath.resolve("foo.txt"), message.getBytes(Charset.forName("UTF-8")));
      final Semaphore semaphore = new Semaphore(0);
      Utf8DecodingListener processListener = new Utf8DecodingListener(semaphore, "", true);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command, "foo.txt");
      pb.setCwd(tmpPath);
      pb.start();
      semaphore.acquireUninterruptibly();
      Assert.assertEquals("Output mismatch", message, processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      javaCwd = Paths.get(System.getProperty("user.dir"));
      Assert.assertNotEquals("java cwd should not be tmp path after process", javaCwd.toRealPath(), tmpPath.toRealPath());
      System.err.println("Completed test changeCwd()");
   }

   @Test
   public void softCloseStdinAfterWrite() throws Exception
   {
      Semaphore semaphore = new Semaphore(0);
      String text = "Hello world!";
      System.err.println("Starting test softCloseStdinAfterWrite()");
      Utf8DecodingListener processListener = new Utf8DecodingListener(semaphore, text, false);
      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      pb.start();
      semaphore.acquireUninterruptibly();
      Assert.assertEquals("Decoding mismatch", text, processListener.decodedStdout.toString());
      Assert.assertEquals("Exit code mismatch", 0, processListener.exitCode);
      Assert.assertFalse("Decoder stdin should not overflow", processListener.stdinOverflow);
      System.err.println("Completed test softCloseStdinAfterWrite()");
   }

   @Test
   public void wantWriteDuringOnStdinReady() throws Exception
   {
      System.err.println("Starting test wantWriteDuringOnStdinReady()");

      final AtomicInteger callCount = new AtomicInteger(0);
      final CountDownLatch onStdinReadyCalled = new CountDownLatch(1);
      final CountDownLatch wantWriteCalled = new CountDownLatch(1);
      NuProcessHandler processListener = new NuAbstractProcessHandler() {
         private NuProcess nuProcess;

         @Override
         public void onPreStart(NuProcess nuProcess) {
            this.nuProcess = nuProcess;
         }

         @Override
         public boolean onStdinReady(ByteBuffer buffer) {
            if (callCount.getAndIncrement() == 0) {
               // For the first callback, signal that we're inside the callback
               // and then wait for wantWrite() to be called by the test thread
               onStdinReadyCalled.countDown();

               try {
                  wantWriteCalled.await(5L, TimeUnit.SECONDS);
               } catch (InterruptedException e) {
                  throw new IllegalStateException("Interrupted while waiting for wantWrite()", e);
               }
            } else {
               // For the second callback, close stdin so the process can complete
               nuProcess.closeStdin(false);
            }

            // No matter which callback, always return false
            return false;
         }
      };

      NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
      NuProcess nuProcess = pb.start();
      //Trigger the first onStdinReady callback
      nuProcess.wantWrite();
      //Wait until onStdinReady is called
      onStdinReadyCalled.await(5L, TimeUnit.SECONDS);
      //Call wantWrite() again
      nuProcess.wantWrite();
      //Let the first onStdinReady callback complete
      wantWriteCalled.countDown();

      try {
         Assert.assertNotEquals(command + " did not complete",
                 nuProcess.waitFor(5L, TimeUnit.SECONDS), Integer.MIN_VALUE);
         Assert.assertEquals("Unexpected onStdinReady call count", 2, callCount.get());
      } finally {
         if (nuProcess.isRunning()) {
            nuProcess.closeStdin(true);
            nuProcess.destroy(false);
         }
      }

      System.err.println("Completed test wantWriteDuringOnStdinReady()");
   }

   private static byte[] getLotsOfBytes()
   {
      return getLotsOfBytes(6000);
   }

   static byte[] getLotsOfBytes(int size)
   {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < size; i++) {
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
      private Semaphore semaphore;

      private Adler32 readAdler32;
      private Adler32 writeAdler32;
      private byte[] bytes;

      LottaProcessListener(Semaphore semaphore)
      {
         this.semaphore = semaphore;

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
         semaphore.release();
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
      private final Semaphore semaphore;
      private final CharBuffer utf8Buffer;
      private final boolean forceCloseStdin;
      private int charsWritten;
      private int charsRead;
      private NuProcess nuProcess;
      public StringBuilder decodedStdout;
      public boolean stdinOverflow;
      public int exitCode;

      Utf8DecodingListener(Semaphore semaphore, String utf8Text, boolean forceCloseStdin)
      {
         super(Charset.forName("UTF-8"));
         this.semaphore = semaphore;
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
         semaphore.release();
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
