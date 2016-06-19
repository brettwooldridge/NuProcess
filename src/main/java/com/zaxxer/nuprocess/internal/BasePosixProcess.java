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

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;

public abstract class BasePosixProcess implements NuProcess
{
   private static final boolean IS_SOFTEXIT_DETECTION;
   private static final ByteBuffer STDIN_CLOSED_PENDING_WRITE_TOMBSTONE = ByteBuffer.allocate(1);

   protected static final IEventProcessor<? extends BasePosixProcess>[] processors;
   protected static int processorRoundRobin;

   protected static AtomicInteger uidSequence = new AtomicInteger();

   protected Logger LOGGER = LoggerFactory.getLogger(this.getClass());

   protected IEventProcessor<? super BasePosixProcess> myProcessor;
   protected volatile NuProcessHandler processHandler;

   protected volatile int uid;
   protected volatile int pid;
   protected volatile boolean isRunning;
   public final AtomicBoolean cleanlyExitedBeforeProcess;
   protected AtomicInteger exitCode;
   protected CountDownLatch exitPending;

   protected final AtomicBoolean isWantStdin;
   protected final AtomicBoolean isWantStdout;
   protected final AtomicBoolean isWantStderr;

   // ******* Input/Output Buffers
   protected ByteBuffer outBuffer;
   protected ByteBuffer errBuffer;
   protected ByteBuffer inBuffer;

   // ******* Stdin/Stdout/Stderr pipe handles
   protected final AtomicInteger stdin;
   protected final AtomicInteger stdout;
   protected final AtomicInteger stderr;
   protected volatile int stdinWidow;
   protected volatile int stdoutWidow;
   protected volatile int stderrWidow;

   protected AtomicBoolean stdinClosing;
   protected boolean outClosed;
   protected boolean errClosed;

   private ConcurrentLinkedQueue<ByteBuffer> pendingWrites;
   private boolean hasProcessExited;

   // Launches threads under which changes to cwd do not affect the cwd of the process.
   public static class LinuxCwdThreadFactory implements ThreadFactory
   {
      private final AtomicInteger threadCount = new AtomicInteger(0);

      @Override
      public Thread newThread(final Runnable r)
      {
         Runnable unshareCwdThenSpawn = new Runnable() {
            @Override
            public void run()
            {
               // This makes a copy of the process's chroot, cwd, and umask and
               // allows the thread to change any of those in a way that doesn't
               // affect the rest of the process.
               int rc = LinuxLibC.unshare(LinuxLibC.CLONE_FS);
               // If this throws, then it bubbles up to whomever called ExecutorService.submit().
               checkReturnCode(rc, "unshare(CLONE_FS) failed");
               r.run();
            }
         };
         Thread newThread = Executors.defaultThreadFactory().newThread(unshareCwdThenSpawn);
         newThread.setName(String.format("NuProcessLinuxCwdChangeable-%d", threadCount.incrementAndGet()));
         return newThread;
      }
   }

   static {
      IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("com.zaxxer.nuprocess.softExitDetection", "true"));

      int numThreads;
      String threads = System.getProperty("com.zaxxer.nuprocess.threads", "auto");
      if ("auto".equals(threads)) {
         numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
      }
      else if ("cores".equals(threads)) {
         numThreads = Runtime.getRuntime().availableProcessors();
      }
      else {
         numThreads = Math.max(1, Integer.parseInt(threads));
      }

      processors = new IEventProcessor<?>[numThreads];

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

      Properties systemProperties = System.getProperties();
      uidSequence = (AtomicInteger) systemProperties.get("com.zaxxer.nuprocess.uidAtomicInteger");
      if (uidSequence == null) {
         uidSequence = new AtomicInteger(0);
         systemProperties.put("com.zaxxer.nuprocess.uidAtomicInteger", uidSequence);
      }
   }

   protected BasePosixProcess(NuProcessHandler processListener)
   {
      this.processHandler = processListener;
      this.isWantStdin = new AtomicBoolean();
      this.isWantStdout = new AtomicBoolean();
      this.isWantStderr = new AtomicBoolean();
      this.cleanlyExitedBeforeProcess = new AtomicBoolean();
      this.exitCode = new AtomicInteger();
      this.exitPending = new CountDownLatch(1);
      this.stdin = new AtomicInteger(-1);
      this.stdout = new AtomicInteger(-1);
      this.stderr = new AtomicInteger(-1);
      this.stdinClosing = new AtomicBoolean();
      this.outClosed = true;
      this.errClosed = true;
   }

   // ************************************************************************
   //                        NuProcess interface methods
   // ************************************************************************

   public NuProcess start(List<String> command, String[] environment, Path cwd)
   {
      uid = uidSequence.incrementAndGet();

      callPreStart();

      String[] commands = command.toArray(new String[command.size()]);

      Pointer posix_spawn_file_actions = createPipes();
      Pointer posix_spawnattr = createPosixSpawnAttributes();

      try {
         int rc = LibC.posix_spawnattr_init(posix_spawnattr);
         checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

         LibC.posix_spawnattr_setflags(posix_spawnattr, getSpawnFlags());

         IntByReference restrict_pid = new IntByReference();
         StringArray commandsArray = new StringArray(commands);
         StringArray environmentArray = new StringArray(environment);
         if (cwd != null) {
            rc = spawnWithCwd(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray, cwd);
         }
         else {
            rc = LibC.posix_spawnp(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray);
         }

         pid = restrict_pid.getValue();

         initializeBuffers();

         if (!checkLaunch()) {
            return null;
         }
 
         checkReturnCode(rc, "Invocation of posix_spawn() failed");

         afterStart();

         registerProcess();

         callStart();

         singleProcessContinue();
      }
      catch (RuntimeException re) {
         // TODO remove from event processor pid map?
         exitCode.set(Integer.MIN_VALUE);
         onExit();
         return null;
      }
      finally {
         LibC.posix_spawnattr_destroy(posix_spawnattr);
         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

         // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
         LibC.close(stdinWidow);
         LibC.close(stdoutWidow);
         LibC.close(stderrWidow);

         deallocateStructures(posix_spawn_file_actions, posix_spawnattr);
      }

      return this;
   }
   
   protected abstract short getSpawnFlags();

   protected abstract int spawnWithCwd(IntByReference restrict_pid,
                                       String restrict_path,
                                       Pointer posix_spawn_file_actions,
                                       Pointer posix_spawnattr,
                                       StringArray commandsArray,
                                       Pointer environmentArray,
                                       Path cwd);

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

   /**
    * Deallocate the memory associated with these structures if the Java process is
    * responsible for freeing them.
    *
    * @param posix_spawn_file_actions a pointer to the posix_spawn_file_actions structure
    * @param posix_spawnattr a pointer to the posix_spawnattr structure
    */
   protected void deallocateStructures(Pointer posix_spawn_file_actions, Pointer posix_spawnattr)
   {
      // Can be overridden by subclasses
   }

   /**
    * For OS's that support launching a process in suspended mode, this is the chance to
    * signal the process to continue.
    *
    */
   protected void singleProcessContinue()
   {
      // Can be overridden by subclasses
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
   public void want(Stream stream)
   {
      int fd;
      switch (stream) {
      case STDIN:
         fd = stdin.get();
         if (fd != -1) {
            isWantStdin.set(true);
            myProcessor.queueWrite(this);
         }
         else {
            throw new IllegalStateException("closeStdin() method has already been called.");
         }
         break;
      case STDOUT:
         fd = stdout.get();
         if (fd != -1) {
            isWantStdout.set(true);
            myProcessor.queueRead(this, Stream.STDOUT);
         }
         break;
      case STDERR:
         fd = stderr.get();
         if (fd != -1) {
            isWantStderr.set(true);
            myProcessor.queueRead(this, Stream.STDERR);
         }
         break;
      }
   }

   /** {@inheritDoc} */
   @Override
   public void closeStdin(boolean force)
   {
      if (force) {
         int fd = stdin.get();
         if (fd != -1) {
            if (myProcessor != null) {
               myProcessor.closeStdin(this);
            }
            LibC.close(fd);
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
      int fd = stdin.get();
      boolean closing = stdinClosing.get();
      if (fd != -1 && !closing) {
         pendingWrites.add(buffer);
         myProcessor.queueWrite(this);
      }
      else {
         throw new IllegalStateException("closeStdin() method has already been called.");
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

   public AtomicInteger getStdin()
   {
      return stdin;
   }

   public AtomicInteger getStdout()
   {
      return stdout;
   }

   public AtomicInteger getStderr()
   {
      return stderr;
   }

   public boolean isStdoutClosed()
   {
      return outClosed;
   }

   public boolean isStderrClosed()
   {
      return errClosed;
   }

   public boolean isWantStdout()
   {
      return isWantStdout.get();
   }

   public boolean isWantStderr()
   {
      return isWantStderr.get();
   }
   
   public void markProcessExited()
   {
      hasProcessExited = true;
   }

   public boolean isProcessExited()
   {
      return hasProcessExited;
   }

   public void setExitCode(int exitCode)
   {
      this.exitCode.set(exitCode);
   }

   public boolean isSoftExit()
   {
      return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
   }

   public long getUid()
   {
      return uid;
   }

   public long getKey(int fd)
   {
      return (long) uid << 32 | ((long) fd & 0xffffffffL);
   }

   public int getFdFromKey(long key)
   {
      return (int) (key & 0xffffffff);
   }

   public void onExit()
   {
      if (exitPending.getCount() == 0) {
         // TODO: handle SIGCHLD
         return;
      }

      try {
         // closeStdin(true);
         close(stdout);
         close(stderr);

         isRunning = false;

         if (outBuffer != null && !outClosed) {
            LOGGER.debug("onExit(): outBuffer != null && !outClosed, calling onStdout() with closed=true");
            outBuffer.flip();
            processHandler.onStdout(outBuffer, true);
         }

         if (errBuffer != null && !errClosed) {
            errBuffer.flip();
            processHandler.onStderr(errBuffer, true);
         }

         if (exitCode.get() != Integer.MAX_VALUE - 1) {
            processHandler.onExit(exitCode.get());
         }
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
      }
      finally {
         exitPending.countDown();
         // Once the last reference to the buffer is gone, Java will finalize the buffer
         // and release the native memory we allocated in initializeBuffers().
         outBuffer = null;
         errBuffer = null;
         inBuffer = null;
         processHandler = null;
      }
   }

   public boolean readStdout(int availability)
   {
      isWantStdout.set(false);

      if (outClosed || availability == 0) {
         return true;
      }

      try {
         if (availability < 0) {
            outClosed = true;
            outBuffer.limit(outBuffer.position());
            processHandler.onStdout(outBuffer, true);
            return false;
         }

         int read = LibC.read(stdout.get(), outBuffer, Math.min(availability, outBuffer.remaining()));
         if (read == 0) {  // EOF
            LOGGER.debug("Read 0 bytes, calling onStdout() with closed=true");
            outClosed = true;
            outBuffer.limit(outBuffer.position());
            processHandler.onStdout(outBuffer, true);
            return false;
         }

         if (read == -1) {  // Unknown error
            LOGGER.debug("Unexpected error during read ({})", Native.getLastError());
            close(stdout);
            outClosed = true;
            return false;
         }

         outBuffer.limit(outBuffer.position() + read);
         outBuffer.position(0);
         boolean more = processHandler.onStdout(outBuffer, false);

         isWantStdout.set(more);
         outBuffer.compact();
         return more;
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         e.printStackTrace(System.err);
         return true;
      }
   }

   public boolean readStderr(int availability)
   {
      isWantStderr.set(false);

      if (errClosed || availability == 0) {
         return true;
      }

      try {
         if (availability < 0) {
            errClosed = true;
            errBuffer.limit(errBuffer.position());
            processHandler.onStderr(errBuffer, true);
            return false;
         }

         int read = LibC.read(stderr.get(), errBuffer, Math.min(availability, errBuffer.remaining()));
         if (read == 0) {  // EOF
            LOGGER.debug("Read 0 bytes, calling onStderr() with closed=true");
            errClosed = true;
            errBuffer.limit(errBuffer.position());
            processHandler.onStderr(errBuffer, true);
            return false;
         }

         if (read == -1) {  // Unknown error
            LOGGER.debug("Unexpected error during read ({})", Native.getLastError());
            close(stderr);
            errClosed = true;
            return false;
         }

         errBuffer.limit(errBuffer.position() + read);
         errBuffer.position(0);
         boolean more = processHandler.onStderr(errBuffer, false);

         isWantStderr.set(more);
         errBuffer.compact();
         return more;
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         e.printStackTrace(System.err);
         return true;
      }
   }

   public boolean writeStdin(int availability)
   {
      int fd = stdin.get();
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
               close(stdin);
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
            isWantStdin.set(false);
            pendingWrites.clear();
            return false;
         } else if (byteBuffer.remaining() > BUFFER_CAPACITY) {
            ByteBuffer slice = byteBuffer.slice();
            slice.limit(BUFFER_CAPACITY);
            inBuffer.put(slice);
            byteBuffer.position(byteBuffer.position() + BUFFER_CAPACITY);
         }
         else {
            inBuffer.put(byteBuffer);
            pendingWrites.poll();
         }

         inBuffer.flip();
         // Recurse
         if (inBuffer.hasRemaining()) {
            return writeStdin(availability);
         }
      }

      if (!isWantStdin.get()) {
         return false;
      }

      try {
         inBuffer.clear();
         boolean wantMore = processHandler.onStdinReady(inBuffer);
         isWantStdin.set(wantMore);
         if (inBuffer.hasRemaining() && availability > 0) {
            // Recurse
            return writeStdin(availability);
         } else {
            return true;
         }
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         return false;
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   protected void afterStart()
   {
      isRunning = true;
   }

   protected void initializeBuffers()
   {
      outClosed = false;
      errClosed = false;

      pendingWrites = new ConcurrentLinkedQueue<ByteBuffer>();

      outBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
      errBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
      inBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);

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

      myProcessor.registerProcess(this);
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

   private void close(AtomicInteger stdX)
   {
      int fd = stdX.getAndSet(-1);
      if (fd != -1) {
         LibC.close(fd);
      }
   }

   protected Pointer createPipes()
   {
      int rc;

      int[] in = new int[2];
      int[] out = new int[2];
      int[] err = new int[2];

      Pointer posix_spawn_file_actions = createPosixSpawnFileActions();

      try {
         rc = LibC.pipe(in);
         checkReturnCode(rc, "Create stdin pipe() failed");
         rc = LibC.pipe(out);
         checkReturnCode(rc, "Create stdout pipe() failed");

         rc = LibC.pipe(err);
         checkReturnCode(rc, "Create stderr pipe() failed");

         // Create spawn file actions
         rc = LibC.posix_spawn_file_actions_init(posix_spawn_file_actions);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_init() failed");

         // Dup the reading end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, in[0], 0);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, in[1]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         stdin.set(in[1]);
         stdinWidow = in[0];

         // Dup the writing end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, out[1], 1);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, out[0]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         stdout.set(out[0]);
         stdoutWidow = out[1];

         // Dup the writing end of the pipe into the sub-process, and close our end
         rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, err[1], 2);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");

         rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, err[0]);
         checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

         stderr.set(err[0]);
         stderrWidow = err[1];

         setNonBlocking(in, out, err);

         return posix_spawn_file_actions;
      }
      catch (RuntimeException e) {
         e.printStackTrace(System.err);

         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
         initFailureCleanup(in, out, err);
         throw e;
      }
   }

   protected abstract Pointer createPosixSpawnFileActions();

   protected abstract Pointer createPosixSpawnAttributes();

   protected void setNonBlocking(int[] in, int[] out, int[] err)
   {
      int rc = LibC.fcntl(in[1], LibC.F_SETFL, LibC.fcntl(in[1], LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stdin handle failed");
      rc = LibC.fcntl(out[0], LibC.F_SETFL, LibC.fcntl(out[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stdout handle failed");
      rc = LibC.fcntl(err[0], LibC.F_SETFL, LibC.fcntl(err[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
      checkReturnCode(rc, "fnctl on stderr handle failed");
   }
   
   private void initFailureCleanup(int[] in, int[] out, int[] err)
   {
      Set<Integer> unique = new HashSet<Integer>();
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
}
