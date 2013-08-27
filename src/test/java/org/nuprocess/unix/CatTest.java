package org.nuprocess.unix;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

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
    public void lotOfProcesses()
    {
        for (int times = 0; times < 100; times++)
        {
            Semaphore[] semaphores = new Semaphore[50];
            AtomicInteger[] sizes = new AtomicInteger[50];
            LottaProcessListener[] listeners = new LottaProcessListener[50];
    
            for (int i = 0; i < semaphores.length; i++)
            {
                semaphores[i] = new Semaphore(0);
                sizes[i] = new AtomicInteger();
                listeners[i] = new LottaProcessListener(semaphores[i], sizes[i]);
                NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"), listeners[i]);
                pb.start();
            }
    
            for (Semaphore sem : semaphores)
            {
                sem.acquireUninterruptibly();
            }
            
            for (AtomicInteger size : sizes)
            {
                Assert.assertEquals("Output size did not match input size", 600000, size.get());
            }
            
            for (LottaProcessListener listen : listeners)
            {
                Assert.assertTrue("Adler32 mismatch between written and read", listen.checkAdlers());
                Assert.assertEquals("Exit code mismatch", 0, listen.getExitCode());
            }
        }
    }

    @Test
    public void lotOfData() throws Exception
    {
        for (int i = 0; i < 100; i++)
        {
            Semaphore semaphore = new Semaphore(0);
            AtomicInteger size = new AtomicInteger();
    
            LottaProcessListener processListener = new LottaProcessListener(semaphore, size);
            NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"), processListener);
            pb.start();
            semaphore.acquireUninterruptibly();
    
            Assert.assertEquals("Output byte count did not match input size, iteration " + i, 600000, size.get());
            Assert.assertTrue("Adler32 mismatch between written and read", processListener.checkAdlers());
        }
    }

    //@Test
    public void badExit()
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
    public void noExecutableFound()
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

    private static class LottaProcessListener extends NuAbstractProcessListener
    {
        private NuProcess nuProcess;
        private StringBuffer sb;
        private int writes;
        private int reads;
        private int[] track;
        private int exitCode;
        private Semaphore semaphore;
        private AtomicInteger size;

        private Adler32 readAdler32;
        private Adler32 writeAdler32;

        LottaProcessListener(Semaphore semaphore, AtomicInteger size)
        {
            this.semaphore = semaphore;
            this.size = size;

            this.readAdler32 = new Adler32();
            this.writeAdler32 = new Adler32();

            this.track = new int[10000];

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
            exitCode = statusCode;
            sb.setLength(0);
            if (size.get() != 600000)
            {
                sb.length();
            }
            semaphore.release();
        }

        @Override
        public void onStdout(ByteBuffer buffer)
        {
            if (buffer == null)
            {
                return;
            }

            track[reads] = buffer.remaining();
            reads++;

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            readAdler32.update(bytes);
            size.addAndGet(bytes.length);

            if (size.get() == 600000 || reads == 10000)
            {
                nuProcess.stdinClose();
            }
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer)
        {
            byte[] bytes = sb.toString().getBytes();
            writeAdler32.update(bytes);

            buffer.put(bytes);
            buffer.flip();
            return ++writes < 10;
        }

        int getExitCode()
        {
            return exitCode;
        }

        boolean checkAdlers()
        {
            return readAdler32.getValue() == writeAdler32.getValue();
        }
    };
}
