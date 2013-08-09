package org.nuprocess.osx;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BaseProcess;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
public class OsxProcess extends BaseProcess
{
    private static final LibC LIBC;

    private CountDownLatch exitPending;

    static
    {
        LIBC = LibC.INSTANCE;

        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessKqueue();
        }
    }

    public OsxProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        super(commands, env, processListener);
        this.exitPending = new CountDownLatch(1);
    }

    @Override
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
            rc = LIBC.posix_spawn(restrict_pid, commands[0], posix_spawn_file_actions, posix_spawnattr, new StringArray(commands), new StringArray(environment));
            checkReturnCode(rc, "Invocation of posix_spawn() failed");
    
            pid = restrict_pid.getValue();

            // After we've spawned, close the unused ends of our pipes (that were dup'd into the child process space)
            close(stdinWidow);
            close(stdoutWidow);
            close(stderrWidow);

            afterStart();

            registerProcess();

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
            LIBC.kill(pid, LibC.SIGTERM);
        }
    }

    @Override
    public void stdinClose()
    {
        if (stdin != 0)
        {
            LIBC.close(stdin);
            stdin = 0;
        }
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    void readStdout(int availability)
    {
        try
        {
            if (availability < 0)
            {
                processListener.onStdout(null);
                return;
            }
            else if (availability == 0)
            {
                return;
            }

            outBuffer.clear();
            int read = LIBC.read(stdout, outBuffer, Math.min(availability, BUFFER_CAPACITY));
            if (read == -1)
            {
                // EOF?
            }
            outBuffer.limit(read);
            processListener.onStdout(outBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    void readStderr(int availability)
    {
        try
        {
            if (availability < 0)
            {
                processListener.onStderr(null);
                return;
            }
            else if (availability == 0)
            {
                return;
            }

            outBuffer.clear();
            int read = LIBC.read(stderr, outBuffer, Math.min(availability, BUFFER_CAPACITY));
            if (read == -1)
            {
                // EOF?
            }
            outBuffer.limit(read);
            processListener.onStderr(outBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    boolean writeStdin(int availability)
    {
        if (availability == 0)
        {
            return false;
        }

        if (inBuffer.position() < inBuffer.limit())
        {
            ByteBuffer slice = inBuffer.slice();
            int wrote = LIBC.write(stdin, slice, Math.min(availability, slice.capacity()));
            if (wrote == -1)
            {
                // EOF?
                return false;
            }

            inBuffer.position(inBuffer.position() + wrote);
            if (userWantsWrite.compareAndSet(false, false))
            {
                return (wrote == slice.capacity() ? false : true);
            }
        }

        try
        {
            inBuffer.clear();
            boolean wantMore = processListener.onStdinReady(inBuffer);
            userWantsWrite.set(wantMore);
            return wantMore;
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            return false;
        }
    }

    void onExit(int statusCode)
    {
        try
        {
            exitCode.set(statusCode);
            processListener.onExit(statusCode);
            exitPending.countDown();
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
        finally
        {
            LIBC.close(stdin);
            LIBC.close(stdout);
            LIBC.close(stderr);

        	outBuffer = null;
        	inBuffer = null;
        	processListener = null;
        }
    }

    protected void close(int fildes)
    {
        LIBC.close(fildes);
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

            stderr = err[0];
            stderrWidow = err[1];

            return posix_spawn_file_actions;
        }
        catch (RuntimeException e)
        {
            LIBC.posix_spawn_file_actions_destroy(posix_spawn_file_actions);

            initFailureCleanup(in, out, err);
            throw e;
        }
    }
}
