package org.nuprocess.windows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuprocess.NuAbstractProcessListener;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessListener;
import org.nuprocess.RunOnlyOnWindows;

/**
 * @author Brett Wooldridge
 */
@RunWith(value=RunOnlyOnWindows.class)
public class CatTest
{
    @AfterClass
    public static void afterClass()
    {
        try
        {
            Thread.sleep(500);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    @Test
    public void lotOfData() throws IOException
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

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe"), processListener);
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

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe", "sdfadsf"), processListener);
        pb.start();
        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Exit code did not match expectation", -1, exitCode.get());
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

    @Test
    public void test4()
    {
        final Semaphore semaphore = new Semaphore(0);
        final AtomicInteger size = new AtomicInteger();

        NuProcessListener processListener = new NuAbstractProcessListener()
        {
            private NuProcess nuProcess;
            private StringBuffer sb;
            private int counter;

            {
                sb = new StringBuffer();
                for (int i = 0; i < 6000; i++)
                {
                    sb.append("1234567890");
                }
            }

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

                size.addAndGet(buffer.remaining());
            }

            @Override
            public boolean onStdinReady(ByteBuffer buffer)
            {
                if (counter++ >= 10)
                {
                    nuProcess.stdinClose();
                    return false;
                }

                buffer.put(sb.toString().getBytes());
                buffer.flip();
                return true;
            }
        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe"), processListener);
        pb.start();
        semaphore.acquireUninterruptibly();

        Assert.assertEquals("Output size did not match input size", 600000, size.get());
    }
}
