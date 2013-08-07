package org.nuprocess.osx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;

import com.sun.jna.Memory;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
public class OsxProcess implements NuProcess
{
    private static final LibC LIBC;
    private static final int BUFFER_CAPACITY = 65536;
    private static final Map<Integer, OsxProcess> pidToProcessMap;
    private static final Map<Integer, OsxProcess> stdinToProcessMap;
    private static final Map<Integer, OsxProcess> stdoutToProcessMap;
    private static final Map<Integer, OsxProcess> stderrToProcessMap;
    private static final ProcessKqueue[] processors;

    private static int processorRoundRobin;

    private String[] args;
    private String[] environment;
    private NuProcessListener processListener;

    private ByteBuffer outBuffer;

    private int stdin;
    private int stdout;
    private int stderr;

    private int stdinWidow;
    private int stdoutWidow;
    private int stderrWidow;

    private int pid;

    static
    {
        LIBC = LibC.INSTANCE;

        pidToProcessMap = new ConcurrentHashMap<Integer, OsxProcess>();
        stdinToProcessMap = new ConcurrentHashMap<Integer, OsxProcess>();
        stdoutToProcessMap = new ConcurrentHashMap<Integer, OsxProcess>();
        stderrToProcessMap = new ConcurrentHashMap<Integer, OsxProcess>();
        
        int numThreads = Integer.getInteger("org.nuprocess.threads",
                                            Boolean.getBoolean("org.nuprocess.threadsEqualCores") ? Runtime.getRuntime().availableProcessors() : 1);
        processors = new ProcessKqueue[numThreads];
        for (int i = 0; i < numThreads; i++)
        {
            processors[i] = new ProcessKqueue(pidToProcessMap, stdinToProcessMap, stdoutToProcessMap, stderrToProcessMap);
        }
    }

    public OsxProcess(List<String> command, String[] env, NuProcessListener processListener)
    {
        this.args = command.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;
        this.outBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
    }

    public NuProcess start()
    {
        Pointer posix_spawn_file_actions = createPipes();
        Pointer posix_spawnattr = new Memory(Pointer.SIZE);

        try
        {
            int rc = LIBC.posix_spawnattr_init(posix_spawnattr);
            checkReturnCode(rc, "Internal call to posix_spawnattr_init() failed");

            // Start the spawned process in suspended mode
            short flags = LibC.POSIX_SPAWN_START_SUSPENDED | LibC.POSIX_SPAWN_CLOEXEC_DEFAULT;
            LIBC.posix_spawnattr_setflags(posix_spawnattr, flags);

            IntByReference restrict_pid = new IntByReference();
            rc = LIBC.posix_spawn(restrict_pid, args[0], posix_spawn_file_actions, posix_spawnattr, new StringArray(args), new StringArray(environment));
            checkReturnCode(rc, "Invocation of posix_spawn() failed");
    
            pid = restrict_pid.getValue();

            args = null;
            environment = null;

            // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
            LIBC.close(stdinWidow);
            LIBC.close(stdoutWidow);
            LIBC.close(stderrWidow);

            pidToProcessMap.put(restrict_pid.getValue(), this);
            stdinToProcessMap.put(stdin, this);
            stdoutToProcessMap.put(stdout, this);
            stderrToProcessMap.put(stderr, this);

            queueChangeList();

            kickstartProcessors();

            callStart();
            
            // Signal the spawned process to continue (unsuspend)
            LIBC.kill(pid, LibC.SIGCONT);

            return this;
        }
        catch (RuntimeException re)
        {
            throw re;
        }
        finally
        {
            LIBC.posix_spawnattr_destroy(posix_spawnattr);
            LIBC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
        }
    }

	@Override
	public int write(byte[] buf) throws IOException
	{
		return write(ByteBuffer.wrap(buf));
	}

	@Override
	public int write(byte[] buf, int off, int len) throws IOException
	{
	    return write(ByteBuffer.wrap(buf, off, len));
	}

	@Override
	public int write(ByteBuffer buf) throws IOException
	{
		ByteBuffer slice = buf.slice();
        int rc = LIBC.write(stdin, slice, slice.capacity());
        if (rc == -1)
        {
            throw new IOException("Failure writing to process pipe");
        }
        if (rc != buf.limit())
        {
            throw new IOException(String.format("Pipe overflow, tried to write %d but could only write %d bytes", buf.limit(), rc));
        }
        return rc;
	}

    @Override
    public void stdinClose()
    {
        LIBC.close(stdin);
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    void callStart()
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

    void readStdout(int available)
    {
        if (available > 0)
        {
            int read = 0;
            while (read < available)
            {
                outBuffer.position(0);
                int rc = LIBC.read(stdout, outBuffer, Math.min(outBuffer.capacity(), available - read));
                if (rc <= 0)
                {
                    break;
                }

                read += rc;
                outBuffer.limit(rc);

                try
                {
                	processListener.onStdout(outBuffer);
                }
                catch (Exception e)
                {
                	// Don't let an exception thrown from the user's handler interrupt us
                }
            }
        }
        else if (available == -1)
        {
            processListener.onStdout(null);
        }
    }

    void readStderr(int available)
    {
        if (available > 0)
        {
            int read = 0;
            while (read < available)
            {
                outBuffer.position(0);
                int rc = LIBC.read(stderr, outBuffer, Math.min(outBuffer.capacity(), available - read));
                if (rc <= 0)
                {
                    break;
                }

                read += rc;
                outBuffer.limit(rc);

                try
                {
                	processListener.onStderr(outBuffer);
                }
                catch (Exception e)
                {
                	// Don't let an exception thrown from the user's handler interrupt us
                }
            }
        }
        else if (available == -1)
        {
            processListener.onStderr(null);
        }
    }

    void writeStdin(int available)
    {
        try
        {
        	processListener.onStdinReady(available);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    void onExit(int statusCode)
    {
        try
        {
            processListener.onExit(statusCode);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
        finally
        {
        	cleanup();
        }
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    private Pointer createPipes()
    {
        int rc = 0;

        int[] in = new int[2];
        int[] out = new int[2];
        int[] err = new int[2];

        Pointer posix_spawn_file_actions = Pointer.NULL;
        try
        {
            rc = LIBC.pipe(in);
            checkReturnCode(rc, "Create pipe() failed");
            
            rc = LIBC.pipe(out);
            checkReturnCode(rc, "Create pipe() failed");
    
            rc = LIBC.pipe(err);
            checkReturnCode(rc, "Create pipe() failed");
    
            // Create spawn file actions
            posix_spawn_file_actions = new Memory(Pointer.SIZE);
            rc = LIBC.posix_spawn_file_actions_init(posix_spawn_file_actions);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_init() failed");
    
            // Dup the reading end of the pipe into the sub-process, and close our end
            rc = LIBC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, in[0], 0);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");
    
            rc = LIBC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, in[1]);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");
            
            stdin = in[1];
            stdinWidow = in[0];
    
            // Dup the writing end of the pipe into the sub-process, and close our end
            rc = LIBC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, out[1], 1);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");
    
            rc = LIBC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, out[0]);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");
    
            stdout = out[0];
            stdoutWidow = out[1];

            // Dup the writing end of the pipe into the sub-process, and close our end
            rc = LIBC.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, err[1], 2);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_adddup2() failed");
    
            rc = LIBC.posix_spawn_file_actions_addclose(posix_spawn_file_actions, err[0]);
            checkReturnCode(rc, "Internal call to posix_spawn_file_actions_addclose() failed");

            stderrWidow = err[1];
            stderr = err[0];

            return posix_spawn_file_actions;
        }
        catch (RuntimeException e)
        {
            LIBC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

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
                LIBC.close(fildes);
            }
        }
    }

    private void cleanup()
    {
    	outBuffer = null;
    	processListener = null;
        pidToProcessMap.remove(pid);
        stdinToProcessMap.remove(stdin);
        stdoutToProcessMap.remove(stdout);
        stderrToProcessMap.remove(stderr);
    }

    private void kickstartProcessors()
    {
        for (int i = 0; i < processors.length; i++)
        {
            if (processors[i].checkAndSetRunning())
            {
                CyclicBarrier spawnBarrier = new CyclicBarrier(2);
                processors[i].setBarrier(spawnBarrier);

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

    private void queueChangeList()
    {
        Kevent[] events = (Kevent[]) new Kevent().toArray(4);

        Kevent.EV_SET(events[0], new NativeLong(pid), 
                      Kevent.EVFILT_PROC,
                      Kevent.EV_ADD | Kevent.EV_ONESHOT,
                      Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[1], new NativeLong(stdout), 
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD | Kevent.EV_ONESHOT,
                      0,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[2], new NativeLong(stderr), 
                      Kevent.EVFILT_READ,
                      Kevent.EV_ADD | Kevent.EV_ONESHOT,
                      0,
                      new NativeLong(0), Pointer.NULL);

        Kevent.EV_SET(events[3], new NativeLong(stdin), 
                      Kevent.EVFILT_WRITE,
                      Kevent.EV_ADD,
                      0,
                      new NativeLong(0), Pointer.NULL);

        synchronized (OsxProcess.class)
        {
            int kqueue = processors[processorRoundRobin].getKqueue();
            processorRoundRobin = (processorRoundRobin + 1) % processors.length;

            int rc = LIBC.kevent(kqueue, events, events.length, null, 0, Pointer.NULL);
            if (rc == -1)
            {
                throw new RuntimeException("Unable to register new events to kqueue");
            }            
        }
    }

    private static void checkReturnCode(int rc, String failureMessage)
    {
        if (rc != 0)
        {
            throw new RuntimeException(failureMessage + ", return code: "+ rc);
        }
    }
}
