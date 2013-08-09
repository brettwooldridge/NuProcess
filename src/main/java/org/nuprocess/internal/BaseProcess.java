package org.nuprocess.internal;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;

public abstract class BaseProcess implements NuProcess
{
    protected static IEventProcessor<? extends BaseProcess>[] processors;
    protected static int processorRoundRobin;

    protected IEventProcessor<? super BaseProcess> myProcessor;
    protected NuProcessListener processListener;

    protected String[] environment;
    protected String[] commands;
    protected AtomicInteger exitCode;

    protected AtomicBoolean userWantsWrite;

    protected ByteBuffer outBuffer;
    protected ByteBuffer inBuffer;

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
    protected BaseProcess(List<String> command, String[] env, NuProcessListener processListener)
    {
        this.commands = command.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;
        this.userWantsWrite = new AtomicBoolean();
        this.exitCode = new AtomicInteger();
    }

    protected abstract void close(int fd);

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
            myProcessor.requeueRead(stdin);
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


    @SuppressWarnings("unchecked")
    protected void registerProcess()
    {
        synchronized (this.getClass())
        {
            myProcessor = (IEventProcessor<? super BaseProcess>) processors[processorRoundRobin]; 
            myProcessor.registerProcess(this);
            processorRoundRobin = (processorRoundRobin + 1) % processors.length;
        }
    }

    protected void afterStart()
    {
        commands = null;
        environment = null;

        outBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        inBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);

        // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
        // close(stdinWidow);
        // close(stdoutWidow);
        // close(stderrWidow);        
    }

    protected void kickstartProcessors()
    {
        for (int i = 0; i < processors.length; i++)
        {
            if (processors[i].checkAndSetRunning())
            {
                CyclicBarrier spawnBarrier = processors[i].getSpawnBarrier();

                Thread t = new Thread(processors[i], "ProcessKqueue" + i);
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
