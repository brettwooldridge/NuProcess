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

package com.zaxxer.nuprocess.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;
import com.zaxxer.nuprocess.windows.NuKernel32.OVERLAPPED;
import com.zaxxer.nuprocess.windows.NuWinNT.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zaxxer.nuprocess.internal.Constants.NUMBER_OF_THREADS;

/**
 * @author Brett Wooldridge
 */
public final class WindowsProcess implements NuProcess
{
   private static final boolean IS_SOFTEXIT_DETECTION;

   private static final int BUFFER_SIZE = 65536;
   private static final String ENV_SYSTEMROOT = "SystemRoot";

   private static final ProcessCompletions[] processors;
   private static int processorRoundRobin;

   private static final String namedPipePathPrefix;
   private static final AtomicInteger namedPipeCounter;

   private volatile ProcessCompletions myProcessor;
   private volatile NuProcessHandler processHandler;

   protected volatile boolean isRunning;
   private AtomicInteger exitCode;
   private CountDownLatch exitPending;

   AtomicBoolean userWantsWrite;
   private volatile boolean writePending;
   private AtomicBoolean stdinClosing;

   private volatile PipeBundle stdinPipe;
   private volatile PipeBundle stdoutPipe;
   private volatile PipeBundle stderrPipe;

   private HANDLE hStdinWidow;
   private HANDLE hStdoutWidow;
   private HANDLE hStderrWidow;

   private ConcurrentLinkedQueue<ByteBuffer> pendingWrites;
   private final ByteBuffer pendingWriteStdinClosedTombstone;

   private volatile boolean inClosed;
   private volatile boolean outClosed;
   private volatile boolean errClosed;

   private PROCESS_INFORMATION processInfo;

   static {
      namedPipePathPrefix = "\\\\.\\pipe\\NuProcess-" + UUID.randomUUID().toString() + "-";
      namedPipeCounter = new AtomicInteger(100);

      IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("com.zaxxer.nuprocess.softExitDetection", "true"));

      processors = new ProcessCompletions[NUMBER_OF_THREADS];
      for (int i = 0; i < NUMBER_OF_THREADS; i++) {
         processors[i] = new ProcessCompletions();
      }

      if (Boolean.parseBoolean(System.getProperty("com.zaxxer.nuprocess.enableShutdownHook", "true"))) {
         Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run()
            {
               for (int i = 0; i < processors.length; i++) {
                  if (processors[i] != null) {
                     processors[i].shutdown();
                  }
               }
            }
         }));
      }
   }

   WindowsProcess(NuProcessHandler processListener)
   {
      this.processHandler = processListener;

      this.userWantsWrite = new AtomicBoolean();
      this.exitCode = new AtomicInteger();
      this.exitPending = new CountDownLatch(1);
      this.outClosed = true;
      this.errClosed = true;
      this.inClosed = true;
      this.stdinClosing = new AtomicBoolean();
      this.pendingWriteStdinClosedTombstone = ByteBuffer.allocate(1);
   }

   // ************************************************************************
   //                        NuProcess interface methods
   // ************************************************************************

   /** {@inheritDoc} */
   @Override
   public int waitFor(long timeout, TimeUnit unit) throws InterruptedException
   {
      if (timeout == 0) {
         exitPending.await();
      }
      else if (!exitPending.await(timeout, unit)) {
         return Integer.MIN_VALUE;
      }

      return exitCode.get();
   }

   /** {@inheritDoc} */
   @Override
   public void wantWrite()
   {
      if (hStdinWidow != null && !NuWinNT.INVALID_HANDLE_VALUE.getPointer().equals(hStdinWidow.getPointer())) {
         userWantsWrite.set(true);
         myProcessor.wantWrite(this);
      }
   }

   /** {@inheritDoc} */
   @Override
   public synchronized void writeStdin(ByteBuffer buffer)
   {
      if (hStdinWidow != null && !NuWinNT.INVALID_HANDLE_VALUE.getPointer().equals(hStdinWidow.getPointer())) {
         pendingWrites.add(buffer);
         if (!writePending) {
            myProcessor.wantWrite(this);
         }
      }
      else {
         throw new IllegalStateException("closeStdin() method has already been called.");
      }
   }

   /** {@inheritDoc} */
   @Override
   public void closeStdin(boolean force)
   {
      if (force) {
         stdinClose();
      } else {
        if (stdinClosing.compareAndSet(false, true)) {
           pendingWrites.add(pendingWriteStdinClosedTombstone);
           if (!writePending) {
              myProcessor.wantWrite(this);
           }
        } else {
           throw new IllegalStateException("closeStdin() method has already been called.");
        }
      }
   }

   /** {@inheritDoc} */
   @Override
   public boolean hasPendingWrites()
   {
      return !pendingWrites.isEmpty();
   }

   /** {@inheritDoc} */
   @Override
   public void destroy(boolean force)
   {
      NuKernel32.TerminateProcess(processInfo.hProcess, Integer.MAX_VALUE);
   }
   
   public int getPID(){
   	   //PointerByReference pointer = new PointerByReference();
	   //return NuKernel32.User32DLL.GetWindowThreadProcessId(null, null);

       return NuKernel32.GetProcessId(this.getPidHandle());
   }

   /** {@inheritDoc} */
   @Override
   public boolean isRunning()
   {
      return isRunning;
   }

   /** {@inheritDoc} */
   @Override
   public void setProcessHandler(NuProcessHandler processHandler)
   {
      this.processHandler = processHandler;
   }

   // ************************************************************************
   //                          Package-scoped methods
   // ************************************************************************

   NuProcess start(List<String> commands, String[] environment, Path cwd)
   {
      callPreStart();

      try {
         createPipes();

         char[] block = getEnvironment(environment);
         Memory env = new Memory(block.length * 3);
         env.write(0, block, 0, block.length);

         STARTUPINFO startupInfo = new STARTUPINFO();
         startupInfo.clear();
         startupInfo.cb = new DWORD(startupInfo.size());
         startupInfo.hStdInput = hStdinWidow;
         startupInfo.hStdError = hStderrWidow;
         startupInfo.hStdOutput = hStdoutWidow;
         startupInfo.dwFlags = NuWinNT.STARTF_USESTDHANDLES;

         processInfo = new PROCESS_INFORMATION();

         DWORD dwCreationFlags = new DWORD(NuWinNT.CREATE_NO_WINDOW | NuWinNT.CREATE_UNICODE_ENVIRONMENT | NuWinNT.CREATE_SUSPENDED);
         char[] cwdChars = (cwd != null) ? Native.toCharArray(cwd.toAbsolutePath().toString()) : null;
         if (!NuKernel32.CreateProcessW(null, getCommandLine(commands), null /*lpProcessAttributes*/, null /*lpThreadAttributes*/, true /*bInheritHandles*/,
                                        dwCreationFlags, env, cwdChars, startupInfo, processInfo)) {
            int lastError = Native.getLastError();
            throw new RuntimeException("CreateProcessW() failed, error: " + lastError);
         }

         afterStart();

         registerProcess();

         callStart();

         NuKernel32.ResumeThread(processInfo.hThread);
      }
      catch (Throwable e) {
         e.printStackTrace();
         onExit(Integer.MIN_VALUE);
      }
      finally {
         NuKernel32.CloseHandle(hStdinWidow);
         NuKernel32.CloseHandle(hStdoutWidow);
         NuKernel32.CloseHandle(hStderrWidow);
      }

      return this;
   }

   HANDLE getPidHandle()
   {
      return processInfo.hProcess;
   }

   PipeBundle getStdinPipe()
   {
      return stdinPipe;
   }

   PipeBundle getStdoutPipe()
   {
      return stdoutPipe;
   }

   PipeBundle getStderrPipe()
   {
      return stderrPipe;
   }

   void readStdout(int transferred)
   {
      if (outClosed) {
         return;
      }

      try {
         if (transferred < 0) {
            outClosed = true;
            stdoutPipe.buffer.flip();
            processHandler.onStdout(stdoutPipe.buffer, true);
            return;
         }
         else if (transferred == 0) {
            return;
         }

         final ByteBuffer buffer = stdoutPipe.buffer;
         buffer.limit(buffer.position() + transferred);
         buffer.position(0);
         processHandler.onStdout(buffer, false);
         buffer.compact();
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         e.printStackTrace();
      }
      if (!stdoutPipe.buffer.hasRemaining()) {
         // The caller's onStdout() callback must set the buffer's position
         // to indicate how many bytes were consumed, or else it will
         // eventually run out of capacity.
         throw new RuntimeException("stdout buffer has no bytes remaining");
      }
   }

   void readStderr(int transferred)
   {
      if (errClosed) {
         return;
      }

      try {
         if (transferred < 0) {
            errClosed = true;
            stderrPipe.buffer.flip();
            processHandler.onStderr(stderrPipe.buffer, true);
            return;
         }
         else if (transferred == 0) {
            return;
         }

         final ByteBuffer buffer = stderrPipe.buffer;
         buffer.limit(buffer.position() + transferred);
         buffer.position(0);
         processHandler.onStderr(buffer, false);
         buffer.compact();
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         e.printStackTrace();
      }
      if (!stderrPipe.buffer.hasRemaining()) {
         // The caller's onStdout() callback must set the buffer's position
         // to indicate how many bytes were consumed, or else it will
         // eventually run out of capacity.
         throw new RuntimeException("stderr buffer has no bytes remaining");
      }
   }

   boolean writeStdin(int transferred)
   {
      if (writePending && transferred == 0) {
         return false;
      }

      stdinPipe.buffer.position(stdinPipe.buffer.position() + transferred);
      if (stdinPipe.buffer.hasRemaining()) {
         NuKernel32.WriteFile(stdinPipe.pipeHandle, stdinPipe.buffer, stdinPipe.buffer.remaining(), null, stdinPipe.overlapped);

         writePending = true;
         return false;
      }

      writePending = false;

      if (!pendingWrites.isEmpty()) {
         stdinPipe.buffer.clear();
         // copy the next buffer into our direct buffer (inBuffer)
         ByteBuffer byteBuffer = pendingWrites.peek();
         if (byteBuffer == pendingWriteStdinClosedTombstone) {
            closeStdin(true);
            userWantsWrite.set(false);
            pendingWrites.clear();
            return false;
         } else if (byteBuffer.remaining() > BUFFER_CAPACITY) {
            ByteBuffer slice = byteBuffer.slice();
            slice.limit(BUFFER_CAPACITY);
            stdinPipe.buffer.put(slice);
            byteBuffer.position(byteBuffer.position() + BUFFER_CAPACITY);
         }
         else {
            stdinPipe.buffer.put(byteBuffer);
            pendingWrites.poll();
         }

         stdinPipe.buffer.flip();

         if (stdinPipe.buffer.hasRemaining()) {
            return true;
         }
      }

      if (userWantsWrite.compareAndSet(true, false)) {

         try {
            final ByteBuffer buffer = stdinPipe.buffer;
            buffer.clear();
            userWantsWrite.set(processHandler.onStdinReady(buffer));

            return true;
         }
         catch (Exception e) {
            // Don't let an exception thrown from the user's handler interrupt us
            e.printStackTrace();
            return false;
         }
      }

      return false;
   }

   void onExit(int statusCode)
   {
      if (exitPending.getCount() == 0) {
         return;
      }

      try {
         isRunning = false;
         exitCode.set(statusCode);
         if (stdoutPipe != null && stdoutPipe.buffer != null && !outClosed) {
            stdoutPipe.buffer.flip();
            processHandler.onStdout(stdoutPipe.buffer, true);
         }
         if (stderrPipe != null && stderrPipe.buffer != null && !errClosed) {
            stderrPipe.buffer.flip();
            processHandler.onStderr(stderrPipe.buffer, true);
         }
         if (statusCode != Integer.MAX_VALUE - 1) {
            processHandler.onExit(statusCode);
         }
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         e.printStackTrace();
      }
      finally {
         exitPending.countDown();

         if (stdinPipe != null) {
            if (!inClosed) {
               NuKernel32.CloseHandle(stdinPipe.pipeHandle);
            }
            // Once the last reference to the buffer is gone, Java will finalize the buffer
            // and release the native memory we allocated in initializeBuffers().
            stdinPipe.buffer = null;
         }

         if (stdoutPipe != null) {
            NuKernel32.CloseHandle(stdoutPipe.pipeHandle);
            stdoutPipe.buffer = null;
         }
         if (stderrPipe != null) {
            NuKernel32.CloseHandle(stderrPipe.pipeHandle);
            stderrPipe.buffer = null;
         }

         if (processInfo != null) {
            NuKernel32.CloseHandle(processInfo.hThread);
            NuKernel32.CloseHandle(processInfo.hProcess);
         }

         stderrPipe = null;
         stdoutPipe = null;
         stdinPipe = null;
         processHandler = null;
      }
   }

   boolean isSoftExit()
   {
      return (outClosed && errClosed && IS_SOFTEXIT_DETECTION);
   }

   void stdinClose()
   {
      if (!inClosed && stdinPipe != null) {
         NuKernel32.CloseHandle(stdinPipe.pipeHandle);
      }
      inClosed = true;
   }

   private void callPreStart()
   {
      try {
         processHandler.onPreStart(this);
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
      }
   }

   private void callStart()
   {
      try {
         processHandler.onStart(this);
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         e.printStackTrace();
      }
   }

   private void createPipes()
   {
      SECURITY_ATTRIBUTES sattr = new SECURITY_ATTRIBUTES();
      sattr.dwLength = new DWORD(sattr.size());
      sattr.bInheritHandle = true;
      sattr.lpSecurityDescriptor = null;

      // ################ STDOUT PIPE ################
      long ioCompletionKey = namedPipeCounter.getAndIncrement();
      WString pipeName = new WString(namedPipePathPrefix + ioCompletionKey);
      hStdoutWidow = NuKernel32.CreateNamedPipeW(pipeName, NuKernel32.PIPE_ACCESS_INBOUND, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                 0 /*nDefaultTimeOut*/, sattr);
      checkHandleValidity(hStdoutWidow);

      HANDLE stdoutHandle = NuKernel32.CreateFile(pipeName, NuWinNT.GENERIC_READ, NuWinNT.FILE_SHARE_READ, null, NuWinNT.OPEN_EXISTING,
                                                  NuWinNT.FILE_ATTRIBUTE_NORMAL | NuWinNT.FILE_FLAG_OVERLAPPED, null /*hTemplateFile*/);
      checkHandleValidity(stdoutHandle);
      stdoutPipe = new PipeBundle(stdoutHandle, ioCompletionKey);
      checkPipeConnected(NuKernel32.ConnectNamedPipe(hStdoutWidow, null));

      // ################ STDERR PIPE ################
      ioCompletionKey = namedPipeCounter.getAndIncrement();
      pipeName = new WString(namedPipePathPrefix + ioCompletionKey);
      hStderrWidow = NuKernel32.CreateNamedPipeW(pipeName, NuKernel32.PIPE_ACCESS_INBOUND, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                 0 /*nDefaultTimeOut*/, sattr);
      checkHandleValidity(hStderrWidow);

      HANDLE stderrHandle = NuKernel32.CreateFile(pipeName, NuWinNT.GENERIC_READ, NuWinNT.FILE_SHARE_READ, null, NuWinNT.OPEN_EXISTING,
                                                  NuWinNT.FILE_ATTRIBUTE_NORMAL | NuWinNT.FILE_FLAG_OVERLAPPED, null /*hTemplateFile*/);
      checkHandleValidity(stderrHandle);
      stderrPipe = new PipeBundle(stderrHandle, ioCompletionKey);
      checkPipeConnected(NuKernel32.ConnectNamedPipe(hStderrWidow, null));

      // ################ STDIN PIPE ################
      ioCompletionKey = namedPipeCounter.getAndIncrement();
      pipeName = new WString(namedPipePathPrefix + ioCompletionKey);
      hStdinWidow = NuKernel32.CreateNamedPipeW(pipeName, NuKernel32.PIPE_ACCESS_OUTBOUND, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                0 /*nDefaultTimeOut*/, sattr);
      checkHandleValidity(hStdinWidow);

      HANDLE stdinHandle = NuKernel32.CreateFile(pipeName, NuWinNT.GENERIC_WRITE, NuWinNT.FILE_SHARE_WRITE, null, NuWinNT.OPEN_EXISTING,
                                                 NuWinNT.FILE_ATTRIBUTE_NORMAL | NuWinNT.FILE_FLAG_OVERLAPPED, null /*hTemplateFile*/);
      checkHandleValidity(stdinHandle);
      stdinPipe = new PipeBundle(stdinHandle, ioCompletionKey);
      checkPipeConnected(NuKernel32.ConnectNamedPipe(hStdinWidow, null));
   }

   private void afterStart()
   {
      pendingWrites = new ConcurrentLinkedQueue<ByteBuffer>();

      outClosed = false;
      errClosed = false;
      inClosed = false;
      isRunning = true;

      stdoutPipe.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
      stderrPipe.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
      stdinPipe.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);

      // Ensure stdin initially has 0 bytes pending write. We'll
      // update this before invoking onStdinReady.
      stdinPipe.buffer.limit(0);
   }

   private void registerProcess()
   {
      int mySlot = 0;
      synchronized (processors) {
         mySlot = processorRoundRobin;
         processorRoundRobin = (processorRoundRobin + 1) % processors.length;
      }

      myProcessor = processors[mySlot];
      myProcessor.registerProcess(this);

      if (myProcessor.checkAndSetRunning()) {
         CyclicBarrier spawnBarrier = myProcessor.getSpawnBarrier();

         Thread t = new Thread(myProcessor, "ProcessIoCompletion" + mySlot);
         t.setDaemon(true);
         t.start();

         try {
            spawnBarrier.await();
         }
         catch (Exception e) {
            throw new RuntimeException(e);
         }
      }
   }

   private char[] getCommandLine(List<String> commands)
   {
      StringBuilder sb = new StringBuilder();
      boolean isFirstCommand = true;
      for (String command : commands) {
         if (isFirstCommand) {
            isFirstCommand = false;
         } else {
            // Prepend a space before the second and subsequent components of the command line.
            sb.append(' ');
         }
         // It's OK to apply CreateProcess escaping to even the first item in the commands
         // list (the path to execute). Since Windows paths cannot contain double-quotes
         // (really!), the logic in WindowsCreateProcessEscape.quote() will either do nothing
         // or simply add double-quotes around the path.
         WindowsCreateProcessEscape.quote(sb, command);
      }
      return Native.toCharArray(sb.toString());
   }

   private char[] getEnvironment(String[] environment)
   {
      Map<String, String> env = new HashMap<String, String>();

      // This SystemRoot handling matches java.lang.ProcessEnvironment.toEnvironmentBlock,
      // which is used by ProcessBuilder when starting processes on Windows
      boolean addSystemRoot = true;

      for (String entry : environment) {
         int ndx = entry.indexOf('=');
         if (ndx != -1) {
            String key = entry.substring(0, ndx);
            env.put(key, (ndx < entry.length() ? entry.substring(ndx + 1) : ""));

            // SystemRoot is sometimes set as SYSTEMROOT, which is also valid, so this needs
            // to use a case-insensitive comparison to detect either permutation
            if (ENV_SYSTEMROOT.equalsIgnoreCase(key)) {
               addSystemRoot = false;
            }
         }
      }

      // If SystemRoot wasn't included in the user-specified environment, copy it from our
      // own environment if it's set there
      if (addSystemRoot) {
         String systemRoot = System.getenv(ENV_SYSTEMROOT);
         if (systemRoot != null) {
            env.put(ENV_SYSTEMROOT, systemRoot);
         }
      }

      return getEnvironmentBlock(env).toCharArray();
   }

   private String getEnvironmentBlock(Map<String, String> env)
   {
      // Sort by name using UPPERCASE collation
      List<Map.Entry<String, String>> list = new ArrayList<Map.Entry<String, String>>(env.entrySet());
      Collections.sort(list, new EntryComparator());

      StringBuilder sb = new StringBuilder(32 * env.size());
      for (Map.Entry<String, String> e : list) {
         sb.append(e.getKey()).append('=').append(e.getValue()).append('\u0000');
      }

      // Add final NUL termination
      sb.append('\u0000').append('\u0000');
      return sb.toString();
   }

   private void checkHandleValidity(HANDLE handle)
   {
      if (NuWinNT.INVALID_HANDLE_VALUE.getPointer().equals(handle)) {
         throw new RuntimeException("Unable to create pipe, error " + Native.getLastError());
      }
   }

   private void checkPipeConnected(int status)
   {
      int lastError;
      if (status == 0 && ((lastError = Native.getLastError()) != NuWinNT.ERROR_PIPE_CONNECTED)) {
         throw new RuntimeException("Unable to connect pipe, error: " + lastError);
      }
   }

   private static final class NameComparator implements Comparator<String>
   {
      @Override
      public int compare(String s1, String s2)
      {
         int len1 = s1.length();
         int len2 = s2.length();
         for (int i = 0; i < Math.min(len1, len2); i++) {
            char c1 = s1.charAt(i);
            char c2 = s2.charAt(i);
            if (c1 != c2) {
               c1 = Character.toUpperCase(c1);
               c2 = Character.toUpperCase(c2);
               if (c1 != c2) {
                  return c1 - c2;
               }
            }
         }

         return len1 - len2;
      }
   }

   private static final class EntryComparator implements Comparator<Map.Entry<String, String>>
   {
      static NameComparator nameComparator = new NameComparator();

      @Override
      public int compare(Map.Entry<String, String> e1, Map.Entry<String, String> e2)
      {
         return nameComparator.compare(e1.getKey(), e2.getKey());
      }
   }

   static final class PipeBundle
   {
      final OVERLAPPED overlapped;
      final long ioCompletionKey;
      final HANDLE pipeHandle;
      ByteBuffer buffer;
      boolean registered;

      PipeBundle(HANDLE pipeHandle, long ioCompletionKey)
      {
         this.pipeHandle = pipeHandle;
         this.ioCompletionKey = ioCompletionKey;
         this.overlapped = new OVERLAPPED();
      }
   }
}
