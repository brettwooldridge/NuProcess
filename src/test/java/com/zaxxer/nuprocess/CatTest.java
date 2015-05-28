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

package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Brett Wooldridge
 */
// @RunWith(value=RunOnlyOnUnix.class)
public class CatTest
{
    private String command;

    @Before
    public void setup()
    {
        command = "cat";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
        {
            command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
        }
    }

    @Test
    public void lotOfProcesses()
    {
        for (int times = 0; times < 20; times++)
        {
            Semaphore[] semaphores = new Semaphore[100];
            LottaProcessListener[] listeners = new LottaProcessListener[100];
    
            for (int i = 0; i < semaphores.length; i++)
            {
                semaphores[i] = new Semaphore(0);
                listeners[i] = new LottaProcessListener(semaphores[i]);
                NuProcessBuilder pb = new NuProcessBuilder(listeners[i], command);
                pb.start();
            }
    
            for (Semaphore sem : semaphores)
            {
                sem.acquireUninterruptibly();
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
    
            LottaProcessListener processListener = new LottaProcessListener(semaphore);
            NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
            pb.start();
            semaphore.acquireUninterruptibly();
    
            Assert.assertTrue("Adler32 mismatch between written and read", processListener.checkAdlers());
        }
    }

    @Test
    public void badExit() throws InterruptedException
    {
        final AtomicInteger exitCode = new AtomicInteger();

        NuProcessHandler processListener = new NuAbstractProcessHandler() {
            @Override
            public void onExit(int statusCode)
            {
                exitCode.set(statusCode);
            }
        };

        NuProcessBuilder pb = new NuProcessBuilder(processListener, command, "/tmp/sdfadsf");
        NuProcess nuProcess = pb.start();
        nuProcess.waitFor(5, TimeUnit.SECONDS);
        if (System.getProperty("os.name").toLowerCase().contains("win"))
        {
            Assert.assertEquals("Exit code did not match expectation", -1, exitCode.get());
        }
        else
        {
            Assert.assertEquals("Exit code did not match expectation", 1, exitCode.get());
        }
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

        NuProcessBuilder pb = new NuProcessBuilder(processListener, "/bin/zxczxc");
        NuProcess process = pb.start();
        semaphore.acquireUninterruptibly();
        Assert.assertFalse("Process incorrectly reported running", process.isRunning());
        Assert.assertEquals("Output did not matched expected result", Integer.MIN_VALUE, exitCode.get());
    }
    
    @Test
    public void callbackOrder() throws InterruptedException
    {
        final List<String> callbacks = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        
        NuProcessHandler handler = new NuProcessHandler() {
            private NuProcess nuProcess;
            
            @Override
            public void onStdout(ByteBuffer buffer) {
                callbacks.add("stdout");
                nuProcess.closeStdin();
            }
            
            @Override
            public boolean onStdinReady(ByteBuffer buffer) {
                callbacks.add("stdin");
                buffer.put("foobar".getBytes()).flip();
                return false;
            }
            
            @Override
            public void onStderr(ByteBuffer buffer) {
                callbacks.add("stderr");
            }
            
            @Override
            public void onStart(NuProcess nuProcess) {
                callbacks.add("start");
                this.nuProcess = nuProcess;
                nuProcess.wantWrite();
            }
            
            @Override
            public void onPreStart(NuProcess nuProcess) {
                callbacks.add("prestart");
            }
            
            @Override
            public void onExit(int exitCode) {
                callbacks.add("exit");
                latch.countDown();
            }
        };
        
        Assert.assertNotNull("process is null", new NuProcessBuilder(handler, command).start());
        latch.await();
        
        Assert.assertEquals("onPreStart was not called first", 0, callbacks.indexOf("prestart"));
        Assert.assertFalse("onExit was called before onStdout", callbacks.indexOf("exit") < callbacks.lastIndexOf("stdout"));
    }

    private static class LottaProcessListener extends NuAbstractProcessHandler
    {
        private static final int WRITES = 10;
        private NuProcess nuProcess;
        private int writes;
        private int size;
        private int exitCode;
        private Semaphore semaphore;

        private Adler32 readAdler32;
        private Adler32 writeAdler32;
        private byte[] bytes;
        

        LottaProcessListener(Semaphore semaphore)
        {
            this.semaphore = semaphore;

            this.readAdler32 = new Adler32();
            this.writeAdler32 = new Adler32();

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
                return;
            }

            size += buffer.remaining();
            if (size == (WRITES * bytes.length))
            {
                nuProcess.closeStdin();
            }

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            readAdler32.update(bytes);
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer)
        {
            writeAdler32.update(bytes);

            buffer.put(bytes);
            buffer.flip();

            return (++writes < WRITES);
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
