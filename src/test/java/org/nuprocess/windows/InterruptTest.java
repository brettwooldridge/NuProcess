package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.nuprocess.NuAbstractProcessHandler;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessHandler;

public class InterruptTest
{

    @Test
    public void testInterrupt1() throws InterruptedException
    {
        final Semaphore semaphore = new Semaphore(0);
        final AtomicInteger exitCode = new AtomicInteger();
        final AtomicInteger count = new AtomicInteger();

        NuProcessHandler processListener = new NuAbstractProcessHandler()
        {

            @Override
            public void onStart(NuProcess nuProcess)
            {
                nuProcess.wantWrite();
            }

            @Override
            public void onExit(int statusCode)
            {
                exitCode.set(statusCode);
                semaphore.release();
            }

            @Override
            public void onStdout(ByteBuffer buffer)
            {
                if (buffer == null)
                {
                    return;
                }

                count.addAndGet(buffer.remaining());
            }

            @Override
            public boolean onStdinReady(ByteBuffer buffer)
            {
                buffer.put("This is a test".getBytes());
                return true;
            }

        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe"), processListener);
        NuProcess process = pb.start();
        while (true)
        {
            if (count.get() > 10000)
            {
                process.destroy();
                break;
            }
            Thread.sleep(20);
        }

        semaphore.acquireUninterruptibly();
		int exit = process.waitFor(2, TimeUnit.SECONDS);
		Assert.assertTrue("Process exit code did not match", (exit == 0 || exit == Integer.MAX_VALUE));
    }

    @Test
    public void chaosMonkey() throws InterruptedException
    {
        NuProcessHandler processListener = new NuAbstractProcessHandler()
        {

            @Override
            public void onStart(NuProcess nuProcess)
            {
                nuProcess.wantWrite();
            }

            @Override
            public void onStdout(ByteBuffer buffer)
            {
                if (buffer == null)
                {
                    return;
                }
            }

            @Override
            public boolean onStdinReady(ByteBuffer buffer)
            {
                buffer.put("This is a test".getBytes());
                return true;
            }

        };

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe"), processListener);
        List<NuProcess> processes = new LinkedList<NuProcess>();
        for (int times = 0; times < 25; times++)
        {
            for (int i = 0; i < 50; i++)
            {
                processes.add(pb.start());
            }
    
            List<NuProcess> deadProcs = new ArrayList<>();
            while (true)
            {
                Thread.sleep(20);
                int dead = (int) (Math.random() * processes.size());
                WindowsProcess bpp = (WindowsProcess) processes.remove(dead);
                deadProcs.add(bpp);
                bpp.destroy();
    
                if (processes.isEmpty())
                {
                	for (int i = 0; i < 50; i++)
                	{
                		int exit = deadProcs.get(i).waitFor(2, TimeUnit.SECONDS);
                		Assert.assertTrue("Process exit code did not match", (exit == 0 || exit == Integer.MAX_VALUE));
                	}
                    break;
                }
            }
        }
    }
}
