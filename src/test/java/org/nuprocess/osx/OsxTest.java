package org.nuprocess.osx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.nuprocess.NuAbstractProcessListener;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessListener;

/**
 * @author Brett Wooldridge
 */
public class OsxTest
{
    @Test
    public void test1() throws IOException
    {
        if (true) return;

        final Semaphore semaphore = new Semaphore(0);
        final StringBuilder sb = new StringBuilder();

        NuProcessListener processListener = new NuProcessListener()
        {
            private boolean done;
            private NuProcess nuProcess;

            public void onStart(NuProcess nuProcess)
            {
                this.nuProcess = nuProcess;
            }

            public void onExit(int statusCode)
            {
                semaphore.release();
            }

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

            public void onStderr(ByteBuffer buffer)
            {
                onStdout(buffer);
            }

            public boolean onStdinReady(int available)
            {
                if (done)
                {
                    nuProcess.stdinClose();
                    return false;
                }

                try
                {
                    nuProcess.write("This is a test message\n".getBytes());
                    done = true;
                    return true;
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onStdinClose()
            {
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

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat", "/tmp/foo"), processListener);
        pb.start();
        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Exit code did not match expectation", 256, exitCode.get());
    }

    @Test
    public void test3()
    {
        if (true) return;

        NuProcessListener processListener = new NuAbstractProcessListener() { };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/zxczxc"), processListener);
        try
        {
            pb.start();
            Assert.fail("An exception should have been thrown");
        }
        catch (RuntimeException e)
        {
            Assert.assertTrue("Unexpected return code", e.getMessage().contains("return code: 2"));
        }
    }
}
