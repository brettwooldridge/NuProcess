package org.nuprocess.osx;

import java.nio.ByteBuffer;
import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BasePosixProcess;
import org.nuprocess.internal.ILibC;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

/**
 * @author Brett Wooldridge
 */
public class OsxProcess extends BasePosixProcess
{
    private static final LibC LIBC;

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

    protected void close(int fildes)
    {
        LIBC.close(fildes);
    }

    @Override
    protected ILibC getLibC()
    {
        return LIBC;
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

}
