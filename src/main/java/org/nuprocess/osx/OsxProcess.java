package org.nuprocess.osx;

import java.nio.ByteBuffer;
import java.util.List;

import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BasePosixProcess;
import org.nuprocess.internal.ILibC;

/**
 * @author Brett Wooldridge
 */
public class OsxProcess extends BasePosixProcess
{
    private static final LibC LIBC;

    private int remainingWrite;
    private int writeOffset;

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
        stdin = close(stdin);
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

            int read = LIBC.read(stdout, outBuffer, Math.min(availability, BUFFER_CAPACITY));
            if (read == -1)
            {
                throw new RuntimeException("Unexpected eof");
                // EOF?
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(outBuffer.getByteArray(0, read));
            processListener.onStdout(byteBuffer);
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

            int read = LIBC.read(stderr, outBuffer, Math.min(availability, BUFFER_CAPACITY));
            if (read == -1)
            {
                // EOF?
                return;
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(outBuffer.getByteArray(0, read));

            processListener.onStderr(byteBuffer);
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

        if (remainingWrite > 0)
        {
            int wrote = LIBC.write(stdin, inBuffer.share(writeOffset), Math.min(remainingWrite, availability));
            if (wrote == -1)
            {
                // EOF?
                return false;
            }

            remainingWrite -= wrote;
            writeOffset += wrote;
            if (remainingWrite > 0)
            {
                return true;
            }

            remainingWrite = 0;
            writeOffset = 0;
        }

        if (userWantsWrite.compareAndSet(false, false))
        {
            return false;
        }

        try
        {
            ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_CAPACITY);
            boolean wantMore = processListener.onStdinReady(byteBuffer);
            userWantsWrite.set(wantMore);

            if (byteBuffer.hasRemaining())
            {
                byte[] array = byteBuffer.array();
                remainingWrite = byteBuffer.remaining();
                inBuffer.write(0, array, 0, remainingWrite);
            }

            return true;
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            return false;
        }
    }

    protected int close(int fildes)
    {
        if (fildes >= 0)
        {
            LIBC.close(fildes);
        }

        return -1;
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
