package org.nuprocess.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

public abstract class BasePosixProcess implements NuProcess
{
    private static final boolean LINUX_USE_VFORK = Boolean.parseBoolean(System.getProperty("org.nuprocess.linuxUseVfork", "true"));
    protected static final boolean IS_MAC = System.getProperty("os.name").toLowerCase().contains("mac");
    protected static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");

    protected static IEventProcessor<? extends BasePosixProcess>[] processors;
    protected static int processorRoundRobin;

    protected IEventProcessor<? super BasePosixProcess> myProcessor;
    protected NuProcessListener processListener;

    protected String[] environment;
    protected String[] commands;
    protected AtomicInteger exitCode;
    protected CountDownLatch exitPending;

    protected AtomicBoolean userWantsWrite;

    protected Pointer outBuffer;
    protected Pointer inBuffer;

    protected AtomicInteger stdin;
    protected AtomicInteger stdout;
    protected AtomicInteger stderr;
    protected volatile int pid;

    protected volatile int stdinWidow;
    protected volatile int stdoutWidow;
    protected volatile int stderrWidow;

    static
    {
        int numThreads = 1;
        String threads = System.getProperty("org.nuprocess.threads", "auto");
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

    protected BasePosixProcess(List<String> command, String[] env, NuProcessListener processListener)
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
    }

    @Override
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

            // Start the spawned process in suspended mode
            short flags = 0;
            if (IS_LINUX && LINUX_USE_VFORK)
            {
                flags = 0x40; // POSIX_SPAWN_USEVFORK
            }
            else if (IS_MAC)
            {
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

            // Signal the spawned process to continue (unsuspend)
            if (IS_MAC)
            {
                LibC.kill(pid, LibC.SIGCONT);
            }
        }
        catch (RuntimeException re)
        {
            // TODO remove from event processor pid map?
            onExit(Integer.MIN_VALUE);
        }
        finally
        {
            LibC.posix_spawnattr_destroy(posix_spawnattr);
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

            if (IS_LINUX) // On OS X closing our side of the pipes causes data to be dropped
            {
                // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
                LibC.close(stdinWidow);
                LibC.close(stdoutWidow);
                LibC.close(stderrWidow);
                Native.free(Pointer.nativeValue(posix_spawn_file_actions));
                Native.free(Pointer.nativeValue(posix_spawnattr));
            }
        }

        return this;
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        exitPending.await();
        return exitCode.get();
    }

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

    @Override
    public void stdinClose()
    {
        close(stdin);
    }

    protected abstract void close(AtomicInteger fd);

    /**
     * @return the pid
     */
    public int getPid()
    {
        return pid;
    }

    /**
     * @return the stdin
     */
    public AtomicInteger getStdin()
    {
        return stdin;
    }

    /**
     * @return the stdout
     */
    public AtomicInteger getStdout()
    {
        return stdout;
    }

    /**
     * @return the stderr
     */
    public AtomicInteger getStderr()
    {
        return stderr;
    }

    @Override
    public void wantWrite()
    {
        int fd = stdin.get();
        if (fd >= 0)
        {
            userWantsWrite.set(true);
            myProcessor.queueWrite(fd);
        }
    }

    protected void callStart()
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

    public void onExit(int statusCode)
    {
        if (exitPending.getCount() == 0)
        {
            // TODO: handle SIGCHLD
            return;
        }

        try
        {
            close(stdin);
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
            Native.free(Pointer.nativeValue(outBuffer));
            Native.free(Pointer.nativeValue(inBuffer));

            processListener = null;
        }
    }

    @SuppressWarnings("unchecked")
    protected void registerProcess()
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

            Thread t = new Thread(myProcessor, "ProcessKqueue" + mySlot);
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

    protected void afterStart()
    {
        commands = null;
        environment = null;

        long peer = Native.malloc(BUFFER_CAPACITY);
        outBuffer = new Pointer(peer);

        peer = Native.malloc(BUFFER_CAPACITY);
        inBuffer = new Pointer(peer);
    }

    protected Pointer createPipes()
    {
        int rc = 0;

        int[] in = new int[2];
        int[] out = new int[2];
        int[] err = new int[2];

        Pointer posix_spawn_file_actions = null;
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
            checkReturnCode(rc, "Create pipe() failed");
            rc = LibC.pipe(out);
            checkReturnCode(rc, "Create pipe() failed");

            rc = LibC.pipe(err);
            checkReturnCode(rc, "Create pipe() failed");

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

            if (IS_LINUX)
            {
                rc = LibC.fcntl(in[1], LibC.F_SETFL, LibC.fcntl(in[1], LibC.F_GETFL) | LibC.O_NONBLOCK);
                rc = LibC.fcntl(out[0], LibC.F_SETFL, LibC.fcntl(out[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
                rc = LibC.fcntl(err[0], LibC.F_SETFL, LibC.fcntl(err[0], LibC.F_GETFL) | LibC.O_NONBLOCK);
            }

            return posix_spawn_file_actions;
        }
        catch (RuntimeException e)
        {
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

            initFailureCleanup(in, out, err);
            throw e;
        }
    }

    protected void initFailureCleanup(int[] in, int[] out, int[] err)
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

    protected void checkReturnCode(int rc, String failureMessage)
    {
        if (rc != 0)
        {
            throw new RuntimeException(failureMessage + ", return code: " + rc);
        }
    }
}
