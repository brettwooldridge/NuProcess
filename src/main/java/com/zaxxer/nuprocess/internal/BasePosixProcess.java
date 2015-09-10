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
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zaxxer.nuprocess.internal.LibC.*;

public abstract class BasePosixProcess implements NuProcess
{
   protected static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");
   protected static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
   private static final boolean LINUX_USE_VFORK = Boolean.parseBoolean(System.getProperty("com.zaxxer.nuprocess.linuxUseVfork", "true"));
   private static final boolean IS_SOFTEXIT_DETECTION;

   protected static final IEventProcessor<? extends BasePosixProcess>[] processors;
   protected static int processorRoundRobin;

   private static ExecutorService linuxCwdExecutorService;

   protected IEventProcessor<? super BasePosixProcess> myProcessor;
   protected volatile NuProcessHandler processHandler;

   protected volatile int pid;
   protected volatile boolean isRunning;
   public final AtomicBoolean cleanlyExitedBeforeProcess;
   protected AtomicInteger exitCode;
   protected CountDownLatch exitPending;

   protected AtomicBoolean userWantsWrite;

   // ******* Input/Output Buffers
   protected ByteBuffer outBuffer;
   protected ByteBuffer errBuffer;
   protected ByteBuffer inBuffer;
   protected Pointer outBufferPointer;
   protected Pointer errBufferPointer;
   protected Pointer inBufferPointer;

   // ******* Stdin/Stdout/Stderr pipe handles
   protected AtomicInteger stdin;
   protected AtomicInteger stdout;
   protected AtomicInteger stderr;
   protected volatile int stdinWidow;
   protected volatile int stdoutWidow;
   protected volatile int stderrWidow;

   protected boolean outClosed;
   protected boolean errClosed;

   private ConcurrentLinkedQueue<ByteBuffer> pendingWrites;
   private int remainingWrite;
   private int writeOffset;

   private Pointer posix_spawn_file_actions;

   // Launches threads under which changes to cwd do not affect the cwd of the process.
   public static class LinuxCwdThreadFactory implements ThreadFactory {
      private final AtomicInteger threadCount = new AtomicInteger(0);

      @Override
      public Thread newThread(final Runnable r) {
         Runnable unshareCwdThenSpawn = new Runnable() {
            @Override
            public void run() {
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

      if (IS_LINUX) {
         ThreadPoolExecutor executor = new ThreadPoolExecutor(
             /* corePoolSize */ numThreads,
             /* maximumPoolSize */ numThreads,
             /* keepAliveTime */ BaseEventProcessor.LINGER_TIME_MS, TimeUnit.MILLISECONDS,
             /* workQueue */ new LinkedBlockingQueue<Runnable>(),
             /* threadFactory */ new LinuxCwdThreadFactory(),
             /* handler */ new ThreadPoolExecutor.DiscardPolicy());
         // Allow going back down to 0 threads after LINGER_TIME_MS.
         executor.allowCoreThreadTimeOut(true);
         linuxCwdExecutorService = executor;
      }
   }

   protected BasePosixProcess(NuProcessHandler processListener) {
      this.processHandler = processListener;
      this.userWantsWrite = new AtomicBoolean();
      this.cleanlyExitedBeforeProcess = new AtomicBoolean();
      this.exitCode = new AtomicInteger();
      this.exitPending = new CountDownLatch(1);
      this.stdin = new AtomicInteger(-1);
      this.stdout = new AtomicInteger(-1);
      this.stderr = new AtomicInteger(-1);
      this.outClosed = true;
      this.errClosed = true;
   }

   // ************************************************************************
   //                        NuProcess interface methods
   // ************************************************************************

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
      int fd = stdin.get();
      if (fd != -1) {
         userWantsWrite.set(true);
         myProcessor.queueWrite(this);
      }
      else {
         throw new IllegalStateException("closeStdin() method has already been called.");
      }
   }

   /** {@inheritDoc} */
   @Override
   public void closeStdin()
   {
      int fd = stdin.getAndSet(-1);
      if (fd != -1) {
         if (myProcessor != null) {
            myProcessor.closeStdin(this);
         }
         LibC.close(fd);
      }
   }

   /** {@inheritDoc} */
   @Override
   public void writeStdin(ByteBuffer buffer)
   {
      int fd = stdin.get();
      if (fd != -1) {
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

   public NuProcess start(List<String> command, String[] environment, Path cwd)
   {
      callPreStart();

      String[] commands = command.toArray(new String[command.size()]);

      Pointer posix_spawn_file_actions = createPipes();

      Pointer posix_spawnattr;
      if (IS_LINUX) {
         long peer = Native.malloc(340);
         posix_spawnattr = new Pointer(peer);
      }
      else {
         posix_spawnattr = new Memory(Pointer.SIZE);
      }

      try {
         int rc = LibC.posix_spawnattr_init(posix_spawnattr);
         checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

         short flags = 0;
         if (IS_LINUX && LINUX_USE_VFORK) {
            flags = 0x40; // POSIX_SPAWN_USEVFORK
         }
         else if (IS_MAC) {
            // Start the spawned process in suspended mode
            flags = LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT;
         }
         LibC.posix_spawnattr_setflags(posix_spawnattr, flags);

         IntByReference restrict_pid = new IntByReference();
         StringArray commandsArray = new StringArray(commands);
         StringArray environmentArray = new StringArray(environment);
         if (cwd != null) {
             if (IS_MAC) {
                 rc = spawnOsxWithCwd(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray, cwd);
             } else if (IS_LINUX) {
                 rc = spawnLinuxWithCwd(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray, cwd);
             } else {
                 throw new RuntimeException("Platform does not support per-thread cwd override");
             }
         } else {
             rc = LibC.posix_spawnp(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, commandsArray, environmentArray);
         }

         pid = restrict_pid.getValue();

         initializeBuffers();

         // This is necessary on Linux because spawn failures are not reflected in the rc, and this will reap
         // any zombies due to launch failure
         if (IS_LINUX) {
            IntByReference ret = new IntByReference();
            int waitpidRc = LibC.waitpid(pid, ret, LibC.WNOHANG);
            int status = ret.getValue();
            boolean cleanExit = waitpidRc == pid && WIFEXITED(status) && WEXITSTATUS(status) == 0;

            if (cleanExit) {
               // If the process already exited cleanly, make sure we run epoll to dispatch any stdout/stderr sent
               // before we tear everything down.
               cleanlyExitedBeforeProcess.set(true);
            } else if (waitpidRc != 0) {
               if (WIFEXITED(status)) {
                  status = WEXITSTATUS(status);
                  if (status == 127) {
                     onExit(Integer.MIN_VALUE);
                  }
                  else {
                     onExit(status);
                  }
               }
               else if (WIFSIGNALED(status)) {
                  onExit(WTERMSIG(status));
               }

               return null;
            }
         }

         checkReturnCode(rc, "Invocation of posix_spawn() failed");

         afterStart();

         registerProcess();

         callStart();

         if (IS_MAC) {
            // Signal the spawned process to continue (unsuspend)
            LibC.kill(pid, LibC.SIGCONT);
         }
      }
      catch (RuntimeException re) {
         // TODO remove from event processor pid map?
         re.printStackTrace(System.err);
         onExit(Integer.MIN_VALUE);
         return null;
      }
      finally {
         LibC.posix_spawnattr_destroy(posix_spawnattr);
         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

         // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
         LibC.close(stdinWidow);
         LibC.close(stdoutWidow);
         LibC.close(stderrWidow);

         if (IS_LINUX) {
            Native.free(Pointer.nativeValue(posix_spawn_file_actions));
            Native.free(Pointer.nativeValue(posix_spawnattr));
         }
      }

      return this;
   }

   private int spawnOsxWithCwd(
      IntByReference restrict_pid,
      String restrict_path,
      Pointer file_actions,
      Pointer /*const posix_spawnattr_t*/restrict_attrp,
      StringArray /*String[]*/argv,
      Pointer /*String[]*/envp,
      Path cwd)
   {
     int cwdBufSize = 1024;
     long peer = Native.malloc(cwdBufSize);
     Pointer oldCwd = new Pointer(peer);
     LibC.getcwd(oldCwd, cwdBufSize);
     String newCwd = cwd.toAbsolutePath().toString();
     int rc = LibC.SYSCALL.syscall(LibC.SYSCALL.SYS___pthread_chdir, newCwd);
     checkReturnCode(rc, "syscall(SYS__pthread_chdir) failed to set current directory");

     try {
       return LibC.posix_spawnp(restrict_pid, restrict_path, file_actions, restrict_attrp, argv, envp);
     } finally {
       rc = LibC.SYSCALL.syscall(LibC.SYSCALL.SYS___pthread_chdir, oldCwd);
       Native.free(Pointer.nativeValue(oldCwd));
       checkReturnCode(rc, "syscall(SYS__pthread_chdir) failed to restore current directory");
     }
   }

   private int spawnLinuxWithCwd(
      final IntByReference restrict_pid,
      final String restrict_path,
      final Pointer file_actions,
      final Pointer /*const posix_spawnattr_t*/restrict_attrp,
      final StringArray /*String[]*/argv,
      final Pointer /*String[]*/envp,
      final Path cwd)
   {
     Future<Integer> setCwdThenSpawnFuture = linuxCwdExecutorService.submit(
         new Callable<Integer>() {
           @Override
           public Integer call() {
               // Set cwd in this thread, which has its cwd state disassociated from the rest of the process.
               int rc = LibC.chdir(cwd.toAbsolutePath().toString());
               // If this throws, it'll be wrapped in an ExecutionException and re-thrown on the thread
               // which calls Future.get().
               checkReturnCode(rc, "chdir() failed");
               // posix_spawnp() will inherit the cwd of this thread.
               //
               // We don't bother restoring cwd, since this thread will either be destroyed or re-used
               // later by the same executor, which will then chdir anyway.
               return LibC.posix_spawnp(restrict_pid, restrict_path, file_actions, restrict_attrp, argv, envp);
           }
         });
     try {
       return setCwdThenSpawnFuture.get();
     } catch (ExecutionException e) {
       Throwable cause = e.getCause();
       if (cause instanceof RuntimeException) {
         throw (RuntimeException) cause;
       } else {
         throw new RuntimeException(cause);
       }
     } catch (InterruptedException e) {
       throw new RuntimeException(e);
     }
   }

   public int getPid()
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
         closeStdin();
         close(stdout);
         close(stderr);

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

         Native.free(Pointer.nativeValue(outBufferPointer));
         Native.free(Pointer.nativeValue(errBufferPointer));
         Native.free(Pointer.nativeValue(inBufferPointer));

         processHandler = null;
      }
   }

   public void readStdout(int availability)
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

         int read = LibC.read(
             stdout.get(),
             outBufferPointer.share(outBuffer.position()),
             Math.min(availability, outBuffer.remaining()));
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
         e.printStackTrace(System.err);
      }
      if (!outBuffer.hasRemaining()) {
         // The caller's onStdout() callback must set the buffer's position
         // to indicate how many bytes were consumed, or else it will
         // eventually run out of capacity.
         throw new RuntimeException("stdout buffer has no bytes remaining");
      }
   }

   public void readStderr(int availability)
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

         int read = LibC.read(
             stderr.get(),
             errBufferPointer.share(errBuffer.position()),
             Math.min(availability, errBuffer.remaining()));
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
         e.printStackTrace(System.err);
      }
      if (!errBuffer.hasRemaining()) {
         // The caller's onStderr() callback must set the buffer's position
         // to indicate how many bytes were consumed, or else it will
         // eventually run out of capacity.
         throw new RuntimeException("stderr buffer has no bytes remaining");
      }
   }

   public boolean writeStdin(int availability)
   {
      int fd = stdin.get();
      if (availability <= 0 || fd == -1) {
         return false;
      }

      if (remainingWrite > 0) {
         int wrote;
         do {
            wrote = LibC.write(fd, inBufferPointer.share(writeOffset), Math.min(remainingWrite, availability));
            if (wrote < 0) {
               int errno = Native.getLastError();
               if (errno == 11 /*EAGAIN on MacOS*/|| errno == 35 /*EAGAIN on Linux*/) {
                  availability /= 4;
                  continue;
               }

               // EOF?
               close(stdin);
               return false;
            }
         }
         while (wrote < 0);

         remainingWrite -= wrote;
         writeOffset += wrote;
         if (remainingWrite > 0) {
            return true;
         }

         inBuffer.clear();
         remainingWrite = 0;
         writeOffset = 0;
      }

      if (!pendingWrites.isEmpty()) {
         // copy the next buffer into our direct buffer (inBuffer)
         ByteBuffer byteBuffer = pendingWrites.peek();
         if (byteBuffer.remaining() > BUFFER_CAPACITY) {
            ByteBuffer slice = byteBuffer.slice();
            slice.limit(BUFFER_CAPACITY);
            inBuffer.put(slice);
            byteBuffer.position(byteBuffer.position() + BUFFER_CAPACITY);
            remainingWrite = BUFFER_CAPACITY;
         }
         else {
            remainingWrite = byteBuffer.remaining();
            inBuffer.put(byteBuffer);
            pendingWrites.poll();
         }

         // Recurse
         if (remainingWrite > 0) {
            return writeStdin(availability);
         }
      }

      if (!userWantsWrite.get()) {
         return false;
      }

      try {
         inBuffer.clear();
         boolean wantMore = processHandler.onStdinReady(inBuffer);
         userWantsWrite.set(wantMore);
         remainingWrite = inBuffer.remaining();

         return true;
      }
      catch (Exception e) {
         // Don't let an exception thrown from the user's handler interrupt us
         return false;
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   private void afterStart()
   {
      isRunning = true;
   }

   private void initializeBuffers()
   {
      outClosed = false;
      errClosed = false;

      pendingWrites = new ConcurrentLinkedQueue<ByteBuffer>();

      long peer = Native.malloc(BUFFER_CAPACITY);
      outBuffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
      outBufferPointer = new Pointer(peer);

      peer = Native.malloc(BUFFER_CAPACITY);
      errBuffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
      errBufferPointer = new Pointer(peer);

      peer = Native.malloc(BUFFER_CAPACITY);
      inBuffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
      inBufferPointer = new Pointer(peer);
   }

   @SuppressWarnings("unchecked")
   private void registerProcess()
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

         Thread t = new Thread(myProcessor, (IS_LINUX ? "ProcessEpoll" : "ProcessKqueue") + mySlot);
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
      }
   }

   private void close(AtomicInteger stdX)
   {
      int fd = stdX.getAndSet(-1);
      if (fd != -1) {
         LibC.close(fd);
      }
   }

   private Pointer createPipes()
   {
      int rc;

      int[] in = new int[2];
      int[] out = new int[2];
      int[] err = new int[2];

      Pointer posix_spawn_file_actions;
      if (IS_LINUX) {
         long peer = Native.malloc(80);
         posix_spawn_file_actions = new Pointer(peer);
      }
      else {
         posix_spawn_file_actions = new Memory(Pointer.SIZE);
      }

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

         if (IS_LINUX || IS_MAC) {
            rc = LibC.fcntl(in[1], LibC.F_SETFL, LibC.fcntl(in[1], LibC.F_GETFL) | LibC.O_NONBLOCK);
            checkReturnCode(rc, "fnctl on stdin handle failed");
            rc = LibC.fcntl(out[0], LibC.F_SETFL, LibC.fcntl(out[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
            checkReturnCode(rc, "fnctl on stdout handle failed");
            rc = LibC.fcntl(err[0], LibC.F_SETFL, LibC.fcntl(err[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
            checkReturnCode(rc, "fnctl on stderr handle failed");
         }

         return posix_spawn_file_actions;
      }
      catch (RuntimeException e) {
         e.printStackTrace(System.err);

         LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
         initFailureCleanup(in, out, err);
         throw e;
      }
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

   private static void checkReturnCode(int rc, String failureMessage)
   {
      if (rc != 0) {
         throw new RuntimeException(failureMessage + ", return code: " + rc + ", last error: " + Native.getLastError());
      }
   }
}
