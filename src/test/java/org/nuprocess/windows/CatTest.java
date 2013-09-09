/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuprocess.NuAbstractProcessHandler;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;
import org.nuprocess.NuProcessHandler;
import org.nuprocess.RunOnlyOnWindows;

/**
 * @author Brett Wooldridge
 */
@RunWith(value=RunOnlyOnWindows.class)
public class CatTest
{
    @Test
    public void lotOfProcesses()
    {
        for (int times = 0; times < 10; times++)
        {
            Semaphore[] semaphores = new Semaphore[250];
            AtomicInteger[] sizes = new AtomicInteger[250];
            LottaProcessListener[] listeners = new LottaProcessListener[250];
    
            for (int i = 0; i < listeners.length; i++)
            {
                semaphores[i] = new Semaphore(0);
                sizes[i] = new AtomicInteger();
                listeners[i] = new LottaProcessListener(semaphores[i], sizes[i]);
                NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe"), listeners[i]);
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
            }
        }
        System.gc();
    }

    @Test
    public void lotOfData()
    {
        for (int i = 0; i < 100; i++)
        {
            Semaphore semaphore = new Semaphore(0);
            AtomicInteger size = new AtomicInteger();
    
            LottaProcessListener processListener = new LottaProcessListener(semaphore, size);
            NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("src\\test\\java\\org\\nuprocess\\windows\\cat.exe"), processListener);
            pb.start();
            semaphore.acquireUninterruptibly();
    
            Assert.assertEquals("Output byte count did not match input size", 600000, size.get());
            Assert.assertTrue("Adler32 mismatch between written and read", processListener.checkAdlers());
        }
    }

    @Test
    public void badExit()
    {
        final Semaphore semaphore = new Semaphore(0);
        final AtomicInteger exitCode = new AtomicInteger();

        NuProcessHandler processListener = new NuAbstractProcessHandler() {
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
    public void noExecutableFound()
    {
        final Semaphore semaphore = new Semaphore(0);
        final AtomicInteger exitCode = new AtomicInteger();

        NuProcessHandler processListener = new NuAbstractProcessHandler() {
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

    private static class LottaProcessListener extends NuAbstractProcessHandler
    {
        private NuProcess nuProcess;
        private int writes;
        private int reads;
        private int[] track;
        private int exitCode;
        private Semaphore semaphore;
        private AtomicInteger size;

        private Adler32 readAdler32;
        private Adler32 writeAdler32;
        private byte[] bytes;
        

        LottaProcessListener(Semaphore semaphore, AtomicInteger size)
        {
            this.semaphore = semaphore;
            this.size = size;

            this.readAdler32 = new Adler32();
            this.writeAdler32 = new Adler32();

            this.track = new int[1000];

            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < 6000; i++)
            {
                sb.append("1234567890");
            }

            bytes = sb.toString().getBytes();
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
            semaphore.release();
        }

        @Override
        public void onStdout(ByteBuffer buffer)
        {
            if (buffer == null)
            {
                if (size.get() != 600000)
                {
                    System.err.println("Premature close of stdout");
                }
                nuProcess.stdinClose();
                return;
            }

            track[reads] = buffer.remaining();
            reads++;

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            readAdler32.update(bytes);
            size.addAndGet(bytes.length);

            if (size.get() == 600000 || reads == 1000)
            {
                nuProcess.stdinClose();
            }
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer)
        {
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
