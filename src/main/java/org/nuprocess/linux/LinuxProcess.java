package org.nuprocess.linux;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BasePosixProcess;
import org.nuprocess.internal.ILibC;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
    private static final LibC LIBC;

    private static final boolean IS_SOFTEXIT_DETECTION;

    private AtomicInteger openFdCount;
    private CountDownLatch exitPending;
    private boolean outClosed;
    private boolean errClosed;

    static
    {
        LIBC = LibC.INSTANCE;

        IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("org.nuprocess.linux.softExitDetection", "true"));

        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessEpoll();
        }
    }

    public LinuxProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        super(commands, env, processListener);
        this.exitPending = new CountDownLatch(1);
        this.openFdCount = new AtomicInteger();
        this.outClosed = true;
        this.errClosed = true;
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        if (exitPending.getCount() > 0)
        {
            // TODO: call native wait
        }
        return exitCode.get();
    }

    @Override
    public void stdinClose()
    {
        if (stdin != 0)
        {
            LIBC.close(stdin);
            // stdin = 0;
        }
    }

    @Override
    public void destroy()
    {
        try
        {
            // destroyProcess.invoke(unixProcessInstance, new Object[] { pid });
        }
        catch (Exception e)
        {
            // eat it
            return;
        }
        finally
        {
            exitPending.countDown();
        }
    }

    // ************************************************************************
    //                             Protected methods
    // ************************************************************************

    @Override
    protected void close(int fd)
    {
        if (fd != 0)
        {
            LIBC.close(fd);
        }
    }

    @Override
    protected ILibC getLibC()
    {
        return LIBC;
    }

    @Override
    protected void afterStart()
    {
        super.afterStart();
        openFdCount.set(2);
        outClosed = false;
        errClosed = false;
    }
    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    void readStdout(boolean closed)
    {
        try
        {
            if (outClosed)
            {
                return;
            }
            else if (closed)
            {
                outClosed = true;
                processListener.onStdout(null);
                return;
            }

            outBuffer.clear();
            int read = LIBC.read(stdout, outBuffer, BUFFER_CAPACITY);
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

    void readStderr(boolean closed)
    {
        try
        {
            if (errClosed)
            {
                return;
            }
            else if (closed)
            {
                errClosed = true;
                processListener.onStderr(null);
                return;
            }

            outBuffer.clear();
            int read = LIBC.read(stderr, outBuffer, BUFFER_CAPACITY);
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

    boolean writeStdin()
    {
        while (true)
        {
            if (inBuffer.limit() < inBuffer.capacity())
            {
                ByteBuffer slice = inBuffer.slice();
                int wrote = LIBC.write(stdin, slice, slice.capacity());
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
    }

    boolean isSoftExit()
    {
        return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
    }
}
