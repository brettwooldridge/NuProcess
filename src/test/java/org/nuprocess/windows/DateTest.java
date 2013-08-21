package org.nuprocess.windows;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuprocess.NuAbstractProcessListener;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessListener;
import org.nuprocess.RunOnlyOnWindows;

@RunWith(value=RunOnlyOnWindows.class)
public class DateTest
{
    @Test
    public void test1() throws Exception
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

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("ipconfig.exe", "/all"), processListener);
        pb.start();
        semaphore.acquireUninterruptibly();
        Assert.assertEquals("Exit code did not match expectation", 0, exitCode.get());
    }

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
}
