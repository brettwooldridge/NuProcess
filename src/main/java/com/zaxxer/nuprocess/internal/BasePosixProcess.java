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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.zaxxer.nuprocess.internal.Constants.NUMBER_OF_THREADS;
import static java.util.concurrent.locks.LockSupport.parkNanos;

@SuppressWarnings("WeakerAccess")
public abstract class BasePosixProcess implements NuProcess
{
   protected static final Logger LOGGER = Logger.getLogger(BasePosixProcess.class.getCanonicalName());

   private static final boolean IS_SOFTEXIT_DETECTION;
   private static final ByteBuffer STDIN_CLOSED_PENDING_WRITE_TOMBSTONE = ByteBuffer.allocate(1);

   protected static final IEventProcessor<? extends BasePosixProcess>[] processors;
   protected static int processorRoundRobin;

   @SuppressWarnings("unused")
   private int exitcode;  // set from native code in JDK 7

   protected IEventProcessor<? super BasePosixProcess> myProcessor;
   protected volatile NuProcessHandler processHandler;

   protected volatile int pid;
   protected volatile boolean isRunning;
   public final AtomicBoolean cleanlyExitedBeforeProcess;
   protected AtomicInteger exitCode;
   protected CountDownLatch exitPending;

   protected AtomicBoolean userWantsWrite;

   // ******* Input/Output Buffers
   private Memory outBufferMemory;
   private Memory errBufferMemory;
   private Memory inBufferMemory;

   protected ByteBuffer outBuffer;
   protected ByteBuffer errBuffer;
   protected ByteBuffer inBuffer;

   // ******* Stdin/Stdout/Stderr pipe handles
   protected ReferenceCountedFileDescriptor stdin;
   protected ReferenceCountedFileDescriptor stdout;
   protected ReferenceCountedFileDescriptor stderr;

   protected volatile int stdinWidow;
   protected volatile int stdoutWidow;
   protected volatile int stderrWidow;

   protected AtomicBoolean stdinClosing;
   protected boolean outClosed;
   protected boolean errClosed;

   private ConcurrentLinkedQueue<ByteBuffer> pendingWrites;

   static {
      IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("com.zaxxer.nuprocess.softExitDetection", "true"));

      processors = new IEventProcessor<?>[NUMBER_OF_THREADS];

      if (Boolean.valueOf(System.getProperty("com.zaxxer.nuprocess.enableShutdownHook", "true"))) {
         Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run()
            {
               for (IEventProcessor<? extends BasePosixProcess> processor : processors) {
                  if (processor != null) {
                     processor.shutdown();
                  }
               }
            }
         }));
      }
   }

   protected BasePosixProcess(NuProcessHandler processListener)
   {
      this.processHandler = processListener;
      this.userWantsWrite = new AtomicBoolean();
      this.cleanlyExitedBeforeProcess = new AtomicBoolean();
      this.exitCode = new AtomicInteger();
      this.exitPending = new CountDownLatch(1);
      this.stdin = new ReferenceCountedFileDescriptor(-1);
      this.stdout = new ReferenceCountedFileDescriptor(-1);
      this.stderr = new ReferenceCountedFileDescriptor(-1);
      this.stdinClosing = new AtomicBoolean();
      this.outClosed = true;
      this.errClosed = true;
   }

   // ************************************************************************
   //                        NuProcess interface methods
   // ************************************************************************

   public abstract NuProcess start(List<String> command, String[] environment, Path cwd);

   /**
    * Runs the process synchronously.
    *
    * Pumping is done on the calling thread, and this method will not return until the process has exited.
    *
    * @since 1.3
    */
   public abstract void run(List<String> command, String[] environment, Path cwd);

   /**
    * Check the launched process and return {@code true} if launch was successful,
    * or {@code false} if there was an error in launch.
    *
    * @return {@code true} on success, {@code false} on failure
    */
   protected boolean checkLaunch()
   {
      // Can be overridden by subclasses for post-launch checks
      return true;
   }

   /** {@inheritDoc} */
   @Override
   public boolean isRunning()
   {
      return isRunning;
   }

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
   public void destroy(boolean force)
   {
      if (isRunning) {
         checkReturnCode(LibC.kill(pid, force ? LibC.SIGKILL : LibC.SIGTERM), "Sending signal failed");
      }
   }

   /** {@inheritDoc} */
   @Override
   public void wantWrite()
   {
      try {
        int fd = stdin.acquire();
        if (fd != -1) {
          userWantsWrite.set(true);
          myProcessor.queueWrite(this);
        }
        else {
          throw new IllegalStateException("closeStdin() method has already been called.");
        }
      } finally {
        stdin.release();
      }
   }

   /** {@inheritDoc} */
   @Override
   public void closeStdin(boolean force)
   {
      if (force) {
         try {
            int fd = stdin.acquire();
            if (fd != -1) {
               if (myProcessor != null) {
                  myProcessor.closeStdin(this);
               }
               stdin.close();
            }
         } finally {
            stdin.release();
         }
      } else {
         if (stdinClosing.compareAndSet(false, true)) {
            pendingWrites.add(STDIN_CLOSED_PENDING_WRITE_TOMBSTONE);
            myProcessor.queueWrite(this);
         } else {
            throw new IllegalStateException("closeStdin() method has already been called.");
         }
      }
   }

   /** {@inheritDoc} */
   @Override
   public void writeStdin(ByteBuffer buffer)
   {
      try {
         int fd = stdin.acquire();
         boolean closing = stdinClosing.get();
         if (fd != -1 && !closing) {
            pendingWrites.add(buffer);
            myProcessor.queueWrite(this);
         }
         else {
            throw new IllegalStateException("closeStdin() method has already been called.");
         }
      } finally {
         stdin.release();
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
   public void setProcessHandler(NuProcessHandler processHandler)
   {
      this.processHandler = processHandler;
   }

   // ************************************************************************
   //                             Public methods
   // ************************************************************************

   public int getPid()
   {
      return pid;
   }
   public int getPID()
   {
      return pid;
   }

   public ReferenceCountedFileDescriptor getStdin()
   {
      return stdin;
   }

   public ReferenceCountedFileDescriptor getStdout()
   {
      return stdout;
   }

   public ReferenceCountedFileDescriptor getStderr()
   {
      return stderr;
   }

   public boolean isSoftExit()
   {
      return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
   }

   public void onExit(int statusCode)
   {
      if (exitPending.getCount() == 0) {
         // TODO: handle SIGCHLD
         return;
      }

      try {
         closeStdin(true);
         stdout.close();
         stderr.close();

         isRunning = false;
         exitCode.set(statusCode);

         if (outBuffer != null && !outClosed) {
            outBuffer.flip();
            processHandler.onStdout(outBuffer, true);
         }

         if (errBuffer != null && !errClosed) {
            errBuffer.flip();
            processHandler.onStderr(errBuffer, true);
         }

         if (statusCode != Integer.MAX_VALUE - 1) {
            processHandler.onExit(statusCode);
         }
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
      }
      finally {
         exitPending.countDown();
         // Once the last reference to the buffer is gone, Java will finalize the buffer
         // and release the native memory we allocated in initializeBuffers().
         outBufferMemory = null;
         errBufferMemory = null;
         inBufferMemory = null;
         outBuffer = null;
         errBuffer = null;
         inBuffer = null;
         processHandler = null;
         Memory.purge();
      }
   }

   public void readStdout(int availability, int fd)
   {
      if (outClosed || availability == 0) {
         return;
      }

      try {
         if (availability < 0) {
            outClosed = true;
            outBuffer.flip();
            processHandler.onStdout(outBuffer, true);
            return;
         }

         int read = LibC.read(fd, outBuffer, Math.min(availability, outBuffer.remaining()));
         if (read == -1) {
            outClosed = true;
            throw new RuntimeException("Unexpected eof");
            // EOF?
         }

         outBuffer.limit(outBuffer.position() + read);
         outBuffer.position(0);
         processHandler.onStdout(outBuffer, false);
         outBuffer.compact();
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         LOGGER.log(Level.WARNING, "Exception thrown from handler", e);
      }
      if (!outBuffer.hasRemaining()) {
         // The caller's onStdout() callback must set the buffer's position
         // to indicate how many bytes were consumed, or else it will
         // eventually run out of capacity.
         throw new RuntimeException("stdout buffer has no bytes remaining");
      }
   }

   public void readStderr(int availability, int fd)
   {
      if (errClosed || availability == 0) {
         return;
      }

      try {
         if (availability < 0) {
            errClosed = true;
            errBuffer.flip();
            processHandler.onStderr(errBuffer, true);
            return;
         }

         int read = LibC.read(fd, errBuffer, Math.min(availability, errBuffer.remaining()));
         if (read == -1) {
            // EOF?
            errClosed = true;
            throw new RuntimeException("Unexpected eof");
         }

         errBuffer.limit(errBuffer.position() + read);
         errBuffer.position(0);
         processHandler.onStderr(errBuffer, false);
         errBuffer.compact();
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         LOGGER.log(Level.WARNING, "Exception thrown from handler", e);
      }
      if (!errBuffer.hasRemaining()) {
         // The caller's onStderr() callback must set the buffer's position
         // to indicate how many bytes were consumed, or else it will
         // eventually run out of capacity.
         throw new RuntimeException("stderr buffer has no bytes remaining");
      }
   }

   public boolean writeStdin(int availability, int fd)
   {
      if (availability <= 0 || fd == -1) {
         return false;
      }

      if (inBuffer.hasRemaining()) {
         int wrote;
         do {
            wrote = LibC.write(fd, inBuffer, Math.min(availability, inBuffer.remaining()));
            if (wrote < 0) {
               int errno = Native.getLastError();
               if (errno == 11 /*EAGAIN on MacOS*/ || errno == 35 /*EAGAIN on Linux*/) {
                  availability /= 4;
                  continue;
               }

               // EOF?
               stdin.close();
               return false;
            }
         }
         while (wrote < 0);

         availability -= wrote;
         inBuffer.position(inBuffer.position() + wrote);
         if (inBuffer.hasRemaining()) {
           return true;
         }
      }

      if (!pendingWrites.isEmpty()) {
         inBuffer.clear();
         // copy the next buffer into our direct buffer (inBuffer)
         ByteBuffer byteBuffer = pendingWrites.peek();
         if (byteBuffer == STDIN_CLOSED_PENDING_WRITE_TOMBSTONE) {
            // We've written everything the user requested, and the user wants to close stdin now.
            closeStdin(true);
            userWantsWrite.set(false);
            pendingWrites.clear();
            return false;
         } else if (byteBuffer != null && byteBuffer.remaining() > BUFFER_CAPACITY) {
            ByteBuffer slice = byteBuffer.slice();
            slice.limit(BUFFER_CAPACITY);
            inBuffer.put(slice);
            byteBuffer.position(byteBuffer.position() + BUFFER_CAPACITY);
         }
         else if (byteBuffer != null) {
            inBuffer.put(byteBuffer);
            pendingWrites.poll();
         }

         inBuffer.flip();
         // Recurse
         if (inBuffer.hasRemaining()) {
            if (availability <= 0) {
               // We can't write now, so we want to be called back again once there is more availability.
               return true;
            }

            return writeStdin(availability, fd);
         }
      }

      if (!userWantsWrite.get()) {
         return false;
      }

      try {
         inBuffer.clear();
         boolean wantMore = processHandler.onStdinReady(inBuffer);
         userWantsWrite.set(wantMore);
         if (inBuffer.hasRemaining() && availability > 0) {
            // Recurse
            return writeStdin(availability, fd);
         } else {
            return true;
         }
      }
      catch (Exception e) {
         Logger.getLogger(getClass().getName()).log(Level.SEVERE, "Exception thrown handling writes to stdin " + processHandler, e);

         // Don't let an exception thrown from the user's handler interrupt us
         return false;
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   protected void afterStart() {
      final long testSleep = Integer.getInteger("nuprocess.test.afterStartSleep", 0);
      if (testSleep > 0) {
         parkNanos(testSleep);
      }

      isRunning = true;
   }

   protected void initializeBuffers()
   {
      outClosed = false;
      errClosed = false;

      pendingWrites = new ConcurrentLinkedQueue<>();

      outBufferMemory = new Memory(BUFFER_CAPACITY);
      outBuffer = outBufferMemory.getByteBuffer(0, outBufferMemory.size()).order(ByteOrder.nativeOrder());

      errBufferMemory = new Memory(BUFFER_CAPACITY);
      errBuffer = errBufferMemory.getByteBuffer(0, outBufferMemory.size()).order(ByteOrder.nativeOrder());

      inBufferMemory = new Memory(BUFFER_CAPACITY);
      inBuffer = inBufferMemory.getByteBuffer(0, outBufferMemory.size()).order(ByteOrder.nativeOrder());

      // Ensure stdin initially has 0 bytes pending write. We'll
      // update this before invoking onStdinReady.
      inBuffer.limit(0);
   }

   @SuppressWarnings("unchecked")
   protected void registerProcess()
   {
      int mySlot;
      synchronized (processors) {
         mySlot = processorRoundRobin;
         processorRoundRobin = (processorRoundRobin + 1) % processors.length;
      }

      myProcessor = (IEventProcessor<? super BasePosixProcess>) processors[mySlot];
      myProcessor.registerProcess(this);

      if (myProcessor.checkAndSetRunning()) {
         CyclicBarrier spawnBarrier = myProcessor.getSpawnBarrier();

         Thread t = new Thread(myProcessor, "ProcessQueue" + mySlot);
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

   protected void callPreStart()
   {
      try {
         processHandler.onPreStart(this);
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
      }
   }

   protected void callStart()
   {
      try {
         processHandler.onStart(this);
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
      }
   }

   protected int[] createPipes()
   {
      int rc;

      int[] in = new int[2];
      int[] out = new int[2];
      int[] err = new int[2];

      try {
         rc = LibC.pipe(in);
         checkReturnCode(rc, "Create stdin pipe() failed");
         rc = LibC.pipe(out);
         checkReturnCode(rc, "Create stdout pipe() failed");
         rc = LibC.pipe(err);
         checkReturnCode(rc, "Create stderr pipe() failed");

         setNonBlocking(in[1], out[0], err[0]);

         stdin = new ReferenceCountedFileDescriptor(in[1]);
         stdout = new ReferenceCountedFileDescriptor(out[0]);
         stderr = new ReferenceCountedFileDescriptor(err[0]);

         stdinWidow  = in[0];
         stdoutWidow = out[1];
         stderrWidow = err[1];

         return new int[] {in[1], out[0], err[0]};
      }
      catch (RuntimeException e) {
         LOGGER.log(Level.SEVERE, "Error creating pipes", e);
         initFailureCleanup(in, out, err);
         throw e;
      }
   }

   protected void initFailureCleanup(int[] in, int[] out, int[] err)
   {
      Set<Integer> unique = new HashSet<>();
      if (in != null) {
         unique.add(in[0]);
         unique.add(in[1]);
      }

      if (out != null) {
         unique.add(out[0]);
         unique.add(out[1]);
      }

      if (err != null) {
         unique.add(err[0]);
         unique.add(err[1]);
      }

      for (int fildes : unique) {
         if (fildes != 0) {
            LibC.close(fildes);
         }
      }
   }

   protected static void checkReturnCode(int rc, String failureMessage)
   {
      if (rc != 0) {
         throw new RuntimeException(failureMessage + ", return code: " + rc + ", last error: " + Native.getLastError());
      }
   }

   private void setNonBlocking(int in, int out, int err)
   {
      int rc = LibC.fcntl(in, LibC.F_SETFL, LibC.fcntl(in, LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stdin handle failed");
      rc = LibC.fcntl(out, LibC.F_SETFL, LibC.fcntl(out, LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stdout handle failed");
      rc = LibC.fcntl(err, LibC.F_SETFL, LibC.fcntl(err, LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stderr handle failed");
   }
}
