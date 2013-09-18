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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;

public abstract class BasePosixProcess implements NuProcess
{
    protected static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    protected static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
    private static final boolean LINUX_USE_VFORK = Boolean.parseBoolean(System.getProperty("com.zaxxer.nuprocess.linuxUseVfork", "true"));
    private static final boolean IS_SOFTEXIT_DETECTION;

    protected static IEventProcessor<? extends BasePosixProcess>[] processors;
    protected static int processorRoundRobin;

    protected IEventProcessor<? super BasePosixProcess> myProcessor;
    protected NuProcessHandler processListener;

    protected String[] environment;
    protected String[] commands;
    protected AtomicInteger exitCode;
    protected CountDownLatch exitPending;

    protected AtomicBoolean userWantsWrite;

    protected ByteBuffer outBuffer;
    protected ByteBuffer inBuffer;

    protected Pointer outBufferPointer;
    protected Pointer inBufferPointer;

    protected AtomicInteger stdin;
    protected AtomicInteger stdout;
    protected AtomicInteger stderr;
    protected volatile int pid;

    protected volatile int stdinWidow;
    protected volatile int stdoutWidow;
    protected volatile int stderrWidow;

    protected boolean outClosed;
    protected boolean errClosed;

    private int remainingWrite;
    private int writeOffset;

    private Pointer posix_spawn_file_actions;

    static
    {
        IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("com.zaxxer.nuprocess.softExitDetection", "true"));

        int numThreads = 1;
        String threads = System.getProperty("com.zaxxer.nuprocess.threads", "auto");
        if ("auto".equals(threads))
        {
            numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
        else if ("cores".equals(threads))
        {
            numThreads = Runtime.getRuntime().availableProcessors();
        }
        else
        {
            numThreads = Math.max(1, Integer.parseInt(threads));
        }

        processors = new IEventProcessor<?>[numThreads];
    }

    protected BasePosixProcess(List<String> command, String[] env, NuProcessHandler processListener)
    {
        this.commands = command.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;
        this.userWantsWrite = new AtomicBoolean();
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
    public int waitFor(long timeout, TimeUnit unit) throws InterruptedException
    {
        if (timeout == 0)
        {
            exitPending.await();
        }
        else if (!exitPending.await(timeout, unit))
        {
            return Integer.MIN_VALUE;
        }

        return exitCode.get();
    }

    /** {@inheritDoc} */
    @Override
    public void destroy()
    {
        if (exitPending.getCount() != 0)
        {
            LibC.kill(pid, LibC.SIGTERM);
            IntByReference exit = new IntByReference();
            LibC.waitpid(pid, exit, 0);
            exitCode.set(exit.getValue());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void wantWrite()
    {
        int fd = stdin.get();
        if (fd != -1)
        {
            userWantsWrite.set(true);
            myProcessor.queueWrite(this);
        }
        else
        {
            throw new IllegalStateException("closeStdin() method has already been called.");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void closeStdin()
    {
        int fd = stdin.get();
        if (fd != -1)
        {
            if (myProcessor != null)
            {
                myProcessor.closeStdin(this);
            }
            close(stdin);
        }
    }

    // ************************************************************************
    //                             Public methods
    // ************************************************************************

    public NuProcess start()
    {
        Pointer posix_spawn_file_actions = createPipes();

        Pointer posix_spawnattr = null;
        if (IS_LINUX)
        {
            long peer = Native.malloc(340);
            posix_spawnattr = new Pointer(peer);
        }
        else
        {
            posix_spawnattr = new Memory(Pointer.SIZE);
        }

        try
        {
            int rc = LibC.posix_spawnattr_init(posix_spawnattr);
            checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

            short flags = 0;
            if (IS_LINUX && LINUX_USE_VFORK)
            {
                flags = 0x40; // POSIX_SPAWN_USEVFORK
            }
            else if (IS_MAC)
            {
                // Start the spawned process in suspended mode
                flags = LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT;
            }
            LibC.posix_spawnattr_setflags(posix_spawnattr, flags);

            IntByReference restrict_pid = new IntByReference();
            rc = LibC.posix_spawn(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, new StringArray(commands), new StringArray(environment));
            checkReturnCode(rc, "Invocation of posix_spawn() failed");

            pid = restrict_pid.getValue();

            afterStart();

            registerProcess();

            callStart();

            if (IS_MAC)
            {
                // Signal the spawned process to continue (unsuspend)
                LibC.kill(pid, LibC.SIGCONT);
            }
        }
        catch (RuntimeException re)
        {
            // TODO remove from event processor pid map?
            re.printStackTrace(System.err);
            onExit(Integer.MIN_VALUE);
        }
        finally
        {
            LibC.posix_spawnattr_destroy(posix_spawnattr);
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

            // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
            LibC.close(stdinWidow);
            LibC.close(stdoutWidow);
            LibC.close(stderrWidow);

            if (IS_LINUX)
            {
                Native.free(Pointer.nativeValue(posix_spawn_file_actions));
                Native.free(Pointer.nativeValue(posix_spawnattr));
            }
        }

        return this;
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
        if (exitPending.getCount() == 0)
        {
            // TODO: handle SIGCHLD
            return;
        }

        try
        {
            closeStdin();
            close(stdout);
            close(stderr);

            exitPending.countDown();
            exitCode.set(statusCode);
            processListener.onExit(statusCode);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
        finally
        {
            Native.free(Pointer.nativeValue(outBufferPointer));
            Native.free(Pointer.nativeValue(inBufferPointer));

            processListener = null;
        }
    }

    public void readStdout(int availability)
    {
        if (outClosed || availability == 0)
        {
            return;
        }

        try
        {
            if (availability < 0)
            {
                outClosed = true;
                processListener.onStdout(null);
                return;
            }
            else if (availability == 0)
            {
                return;
            }

            int read = LibC.read(stdout.get(), outBufferPointer, Math.min(availability, BUFFER_CAPACITY));
            if (read == -1)
            {
                outClosed = true;
                throw new RuntimeException("Unexpected eof");
                // EOF?
            }

            outBuffer.position(0);
            outBuffer.limit(read);
            processListener.onStdout(outBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            e.printStackTrace(System.err);
        }
    }

    public void readStderr(int availability)
    {
        if (errClosed || availability == 0)
        {
            return;
        }

        try
        {
            if (availability < 0)
            {
                errClosed = true;
                processListener.onStderr(null);
                return;
            }

            int read = LibC.read(stderr.get(), outBufferPointer, Math.min(availability, BUFFER_CAPACITY));
            if (read == -1)
            {
                // EOF?
                errClosed = true;
                throw new RuntimeException("Unexpected eof");
            }

            outBuffer.position(0);
            outBuffer.limit(read);
            processListener.onStderr(outBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            e.printStackTrace(System.err);
        }
    }

    public boolean writeStdin(int availability)
    {
        if (availability <= 0)
        {
            return false;
        }

        int fd = stdin.get();
        if (remainingWrite > 0 && fd != -1)
        {
            int wrote = 0;
            do {
                wrote = LibC.write(fd, inBufferPointer.share(writeOffset), Math.min(remainingWrite, availability));
                if (wrote < 0)
                {
                    int errno = Native.getLastError();
                    if (errno == 11 /*EAGAIN on MacOS*/ || errno == 35 /*EAGAIN on Linux*/)
                    {
                        availability /= 4;
                        continue;
                    }
    
                    // EOF?
                    close(stdin);
                    return false;
                }
            } while (wrote < 0);

            remainingWrite -= wrote;
            writeOffset += wrote;
            if (remainingWrite > 0)
            {
                return true;
            }

            remainingWrite = 0;
            writeOffset = 0;
        }

        if (!userWantsWrite.get())
        {
            return false;
        }

        try
        {
            inBuffer.clear();
            boolean wantMore = processListener.onStdinReady(inBuffer);
            userWantsWrite.set(wantMore);
            remainingWrite = inBuffer.remaining();

            return true;
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            return false;
        }
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************
    
    private void afterStart()
    {
        commands = null;
        environment = null;
        outClosed = false;
        errClosed = false;

        long peer = Native.malloc(BUFFER_CAPACITY);
        outBuffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
        outBufferPointer = new Pointer(peer);

        peer = Native.malloc(BUFFER_CAPACITY);
        inBuffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
        inBufferPointer = new Pointer(peer);
    }

    @SuppressWarnings("unchecked")
    private void registerProcess()
    {
        int mySlot = 0;
        synchronized (processors)
        {
            mySlot = processorRoundRobin;
            processorRoundRobin = (processorRoundRobin + 1) % processors.length;
        }

        myProcessor = (IEventProcessor<? super BasePosixProcess>) processors[mySlot];
        myProcessor.registerProcess(this);

        if (myProcessor.checkAndSetRunning())
        {
            CyclicBarrier spawnBarrier = myProcessor.getSpawnBarrier();

            Thread t = new Thread(myProcessor, (IS_LINUX ? "ProcessEpoll" : "ProcessKqueue") + mySlot);
            t.setDaemon(true);
            t.start();

            try
            {
                spawnBarrier.await();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    private void callStart()
    {
        try
        {
            processListener.onStart(this);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    private void close(AtomicInteger stdX)
    {
        int fd = stdX.getAndSet(-1);
        if (fd != -1)
        {
            LibC.close(fd);
        }
    }

    private Pointer createPipes()
    {
        int rc = 0;

        int[] in = new int[2];
        int[] out = new int[2];
        int[] err = new int[2];

        posix_spawn_file_actions = null;
        if (IS_LINUX)
        {
            long peer = Native.malloc(80);
            posix_spawn_file_actions = new Pointer(peer);
        }
        else
        {
            posix_spawn_file_actions = new Memory(Pointer.SIZE);
        }

        try
        {
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

            if (IS_LINUX || IS_MAC)
            {
                rc = LibC.fcntl(in[1], LibC.F_SETFL, LibC.fcntl(in[1], LibC.F_GETFL) | LibC.O_NONBLOCK);
                checkReturnCode(rc, "fnctl on stdin handle failed");
                rc = LibC.fcntl(out[0], LibC.F_SETFL, LibC.fcntl(out[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
                checkReturnCode(rc, "fnctl on stdout handle failed");
                rc = LibC.fcntl(err[0], LibC.F_SETFL, LibC.fcntl(err[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
                checkReturnCode(rc, "fnctl on stderr handle failed");
            }

            return posix_spawn_file_actions;
        }
        catch (RuntimeException e)
        {
            e.printStackTrace(System.err);
            
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
            initFailureCleanup(in, out, err);
            throw e;
        }
    }

    private void initFailureCleanup(int[] in, int[] out, int[] err)
    {
        Set<Integer> unique = new HashSet<Integer>();
        if (in != null)
        {
            unique.add(in[0]);
            unique.add(in[1]);
        }

        if (out != null)
        {
            unique.add(out[0]);
            unique.add(out[1]);
        }

        if (err != null)
        {
            unique.add(err[0]);
            unique.add(err[1]);
        }

        for (int fildes : unique)
        {
            if (fildes != 0)
            {
                LibC.close(fildes);
            }
        }
    }

    private void checkReturnCode(int rc, String failureMessage)
    {
        if (rc != 0)
        {
            throw new RuntimeException(failureMessage + ", return code: " + rc + ", last error: " + Native.getLastError());
        }
    }
}
