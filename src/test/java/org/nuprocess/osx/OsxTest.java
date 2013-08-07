package org.nuprocess.osx;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

import org.junit.Assert;
import org.junit.Test;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessListener;

public class OsxTest
{
    @Test
    public void test1()
    {
        final Semaphore semaphore = new Semaphore(0);
        final StringBuilder sb = new StringBuilder();

        NuProcessListener processListener = new NuProcessListener()
        {
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
            
            public void onExit(int statusCode)
            {
                semaphore.release();
            }
        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"), processListener);
        NuProcess process = pb.start();
        Assert.assertNotNull(process);

        process.stdinWrite("This is a test message\n");

        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Output did not matched expected result", "This is a test message\n", sb.toString());
    }

    @Test
    public void test2()
    {
        NuProcessListener processListener = new NuProcessListener()
        {
            public void onStdout(ByteBuffer buffer)
            {
            }
            
            public void onStderr(ByteBuffer buffer)
            {
            }
            
            public void onExit(int statusCode)
            {
            }
        };

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
