package org.nuprocess.linux;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BasePosixProcess;
import org.nuprocess.internal.LibC;

import com.sun.jna.Native;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
    private static final boolean IS_SOFTEXIT_DETECTION;

    private AtomicInteger openFdCount;
    private boolean outClosed;
    private boolean errClosed;

    private int remainingWrite;
    private int writeOffset;

    // For debugging
    private volatile int written;
    private volatile int origStdin;
    private volatile int origStdout;
    private volatile int origStderr;

    static
    {
        LibEpoll.sigignore(LibEpoll.SIGPIPE);

        // TODO: install signal handler for SIGCHLD, and call onExit() when received, call the default (JVM) hook if the PID is not ours

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
    public void stdinClose()
    {
        int fd = stdin.get();
        if (fd >= 0)
        {
            myProcessor.closeStdin(fd);
            close(stdin);
        }
    }

    // ************************************************************************
    //                             Protected methods
    // ************************************************************************


    @Override
    protected void afterStart()
    {
        super.afterStart();
        openFdCount.set(2);
        outClosed = false;
        errClosed = false;
        origStdin = stdin.get();
        origStdout = stdout.get();
        origStderr = stderr.get();
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    void readStdout(boolean closed)
    {
        if (outClosed)
        {
            return;
        }

        try
        {
            if (closed)
            {
                outClosed = true;
                processListener.onStdout(null);
                return;
            }

            int read = LibC.read(stdout.get(), outBuffer, BUFFER_CAPACITY);
            if (read == -1)
            {
                // EOF?
                outClosed = true;
                return; // throw new RuntimeException("Unexpected eof");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(outBuffer.getByteArray(0, read));
            processListener.onStdout(byteBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    void readStderr(boolean closed)
    {
        if (errClosed)
        {
            return;
        }

        try
        {
            if (closed)
            {
                errClosed = true;
                processListener.onStderr(null);
                return;
            }

            int read = LibC.read(stderr.get(), outBuffer, BUFFER_CAPACITY);
            if (read == -1)
            {
                // EOF?
                errClosed = true;
                return; // throw new RuntimeException("Unexpected eof");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(outBuffer.getByteArray(0, read));
            processListener.onStderr(byteBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    boolean writeStdin()
    {
        if (remainingWrite > 0)
        {
            int wrote = LibC.write(stdin.get(), inBuffer.share(writeOffset), Math.min(remainingWrite, BUFFER_CAPACITY));
            if (wrote < 0)
            {
                // EOF?
                stdinClose();
                int rc = Native.getLastError();
                return false;
            }

            written += wrote;

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

    boolean isSoftExit()
    {
        return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
    }

    @Override
    public int hashCode()
    {
        return pid;
    }

    @Override
    public boolean equals(Object obj)
    {
        return pid == ((LinuxProcess) obj).getPid();
    }
}
