package org.nuprocess.unix;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuprocess.NuAbstractProcessListener;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessListener;
import org.nuprocess.RunOnlyOnUnix;

/**
 * @author Brett Wooldridge
 */
@RunWith(value=RunOnlyOnUnix.class)
public class CatTest
{
    @Test
    public void test1() throws IOException
    {
        // if (true) return;

        final Semaphore semaphore = new Semaphore(0);
        final StringBuilder sb = new StringBuilder();

        NuProcessListener processListener = new NuProcessListener()
        {
            private boolean done;
            private NuProcess nuProcess;

            @Override
            public void onStart(NuProcess nuProcess)
            {
                this.nuProcess = nuProcess;
                nuProcess.wantWrite();
            }

            @Override
            public void onExit(int statusCode)
            {
                semaphore.release();
            }

            @Override
            public void onStdout(ByteBuffer buffer)
            {
                if (buffer == null)
                {
                    return;
                }

                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                sb.append(new String(bytes));
            }

            @Override
            public void onStderr(ByteBuffer buffer)
            {
                onStdout(buffer);
            }

            @Override
            public void onStdinClose()
            {
            }

            @Override
            public boolean onStdinReady(ByteBuffer buffer)
            {
                if (done)
                {
                    nuProcess.stdinClose();
                    return false;
                }

                buffer.put("This is a test message\n".getBytes());
                buffer.flip();
                done = true;
                return true;
            }
        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"), processListener);
        NuProcess process = pb.start();
        Assert.assertNotNull(process);

        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Output did not matched expected result", "This is a test message\n", sb.toString());
    }

    @Test
    public void test2()
    {
        final Semaphore semaphore = new Semaphore(0);
        final AtomicInteger exitCode = new AtomicInteger();

        NuProcessListener processListener = new NuAbstractProcessListener() {
            @Override
            public void onExit(int statusCode)
            {
                exitCode.set(statusCode);
                semaphore.release();
            }
        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat", "/tmp/sdfadsf"), processListener);
        pb.start();
        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Exit code did not match expectation", 1, exitCode.get());
    }

    @Test
    public void test3()
    {
        final Semaphore semaphore = new Semaphore(0);
        final AtomicInteger exitCode = new AtomicInteger();

        NuProcessListener processListener = new NuAbstractProcessListener() {
            @Override
            public void onExit(int statusCode)
            {
                exitCode.set(statusCode);
                semaphore.release();
            }
        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/zxczxc"), processListener);
        pb.start();
        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Output did not matched expected result", Integer.MIN_VALUE, exitCode.get());
    }
}
