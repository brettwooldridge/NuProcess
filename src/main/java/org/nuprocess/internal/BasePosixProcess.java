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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

public abstract class BasePosixProcess implements NuProcess
{
    private static final boolean LINUX_USE_VFORK = Boolean.getBoolean("org.nuprocess.linuxUseVfork");
    protected static final String OSNAME = System.getProperty("os.name").toLowerCase();

    protected static IEventProcessor<? extends BasePosixProcess>[] processors;
    protected static int processorRoundRobin;

    protected IEventProcessor<? super BasePosixProcess> myProcessor;
    protected NuProcessListener processListener;

    protected String[] environment;
    protected String[] commands;
    protected AtomicInteger exitCode;
    protected CountDownLatch exitPending;

    protected AtomicBoolean userWantsWrite;

//    protected ByteBuffer outBuffer;
//    protected ByteBuffer inBuffer;
    protected Pointer outBuffer;
    protected Pointer inBuffer;

    protected int stdin;
    protected int stdout;
    protected int stderr;
    protected int pid;

    protected int stdinWidow;
    protected int stdoutWidow;
    protected int stderrWidow;

    static
    {
        int numThreads = Integer.getInteger("org.nuprocess.threads",
                                            Boolean.getBoolean("org.nuprocess.threadsEqualCores") ? Runtime.getRuntime().availableProcessors() : 1);
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
    }

    @Override
    public NuProcess start()
    {
        PointerByReference posix_spawn_file_actions = createPipes();
        PointerByReference posix_spawnattr = new PointerByReference(); //  Memory(340); // Pointer.SIZE);

        try
        {
            int rc = LibC.posix_spawnattr_init(posix_spawnattr);
            checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

            // Start the spawned process in suspended mode
            short flags = 0;
            if (OSNAME.contains("linux") && LINUX_USE_VFORK)
            {
                flags = 0x40; // POSIX_SPAWN_USEVFORK
            }
            else if (OSNAME.contains("mac"))
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
            if (OSNAME.contains("mac"))
            {
                LibC.kill(pid, LibC.SIGCONT);
            }
        }
        catch (RuntimeException re)
        {
            onExit(Integer.MIN_VALUE);
        }
        finally
        {
            LibC.posix_spawnattr_destroy(posix_spawnattr);
            LibC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
            
            // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
            //close(stdinWidow);
            //close(stdoutWidow);
            //close(stderrWidow);
        }
        
        return this;
    }

    protected abstract int close(int fd);

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
    public int getStdin()
    {
        return stdin;
    }


    /**
     * @return the stdout
     */
    public int getStdout()
    {
        return stdout;
    }


    /**
     * @return the stderr
     */
    public int getStderr()
    {
        return stderr;
    }

    @Override
    public void wantWrite()
    {
        if (stdin != 0)
        {
            userWantsWrite.set(true);
            myProcessor.queueWrite(stdin);
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
            return;
        }

        try
        {
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
            stdin = close(stdin);
            stdout = close(stdout);
            stderr = close(stderr);
            stdinWidow = close(stdinWidow);
            stdoutWidow = close(stdoutWidow);
            stderrWidow = close(stderrWidow);

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

    protected PointerByReference createPipes()
    {
        int rc = 0;

        int[] in = new int[2];
        int[] out = new int[2];
        int[] err = new int[2];

        PointerByReference posix_spawn_file_actions = new PointerByReference(); //.NULL;
        try
        {
            rc = LibC.pipe(in);
            checkReturnCode(rc, "Create pipe() failed");
            
            rc = LibC.pipe(out);
            checkReturnCode(rc, "Create pipe() failed");
    
            rc = LibC.pipe(err);
            checkReturnCode(rc, "Create pipe() failed");
    
            // Create spawn file actions
            posix_spawn_file_actions = new PointerByReference(); // Memory(80); // Pointer.SIZE);
            rc = LibC.posix_spawn_file_actions_init(posix_spawn_file_actions);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_init() failed");
    
            // Dup the reading end of the pipe into the sub-process, and close our end
            rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, in[0], 0);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");
    
            rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, in[1]);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

            stdin = in[1];
            stdinWidow = in[0];
    
            // Dup the writing end of the pipe into the sub-process, and close our end
            rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, out[1], 1);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");
    
            rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, out[0]);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");
    
            stdout = out[0];
            stdoutWidow = out[1];

            // Dup the writing end of the pipe into the sub-process, and close our end
            rc = LibC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, err[1], 2);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");
    
            rc = LibC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, err[0]);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

            stderr = err[0];
            stderrWidow = err[1];

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
                close(fildes);
            }
        }
    }

    protected void checkReturnCode(int rc, String failureMessage)
    {
        if (rc != 0)
        {
            throw new RuntimeException(failureMessage + ", return code: "+ rc);
        }
    }
}
