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
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Brett Wooldridge
 */
public class DirectWrite
{
    private String command;

    @Before
    public void setup()
    {
        command = "/bin/cat";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
        {
            command = "src\\test\\java\\com\\zaxxer\\nuprocess\\cat.exe";
        }
    }

    @Test
    public void testDirectWrite() throws InterruptedException
    {
        ProcessHandler1 processListener = new ProcessHandler1();
        NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
        NuProcess nuProcess = pb.start();
        nuProcess.waitFor(0, TimeUnit.SECONDS);
        Assert.assertEquals("Results did not match", "This is a test", processListener.result);
    }

    @Test
    public void testDirectWriteBig() throws InterruptedException
    {
        ProcessHandler2 processListener = new ProcessHandler2();
        NuProcessBuilder pb = new NuProcessBuilder(processListener, command);
        NuProcess nuProcess = pb.start();
        nuProcess.waitFor(0, TimeUnit.SECONDS);
        Assert.assertEquals("Checksums did not match", processListener.checksum, processListener.checksum2);
    }

    private static class ProcessHandler1 extends NuAbstractProcessHandler
    {
        private NuProcess nuProcess;
        String result;

        @Override
        public void onStart(NuProcess nuProcess)
        {
            this.nuProcess = nuProcess;

            ByteBuffer buffer = ByteBuffer.allocate(256);
            buffer.put("This is a test".getBytes());
            buffer.flip();

            System.out.println("Writing: This is a test");
            nuProcess.writeStdin(buffer);
        }

        @Override
        public void onStdout(ByteBuffer buffer)
        {
            if (buffer == null)
            {
                return;
            }

            byte[] chars = new byte[buffer.remaining()];
            buffer.get(chars);
            result = new String(chars);
            System.out.println("Read: " + result);
            nuProcess.closeStdin();
        }
    }

    private static class ProcessHandler2 extends NuAbstractProcessHandler
    {
        private NuProcess nuProcess;
        int checksum;
        int checksum2;

        @Override
        public void onStart(NuProcess nuProcess)
        {
            this.nuProcess = nuProcess;

            ByteBuffer buffer = ByteBuffer.allocate(1024 * 128);
            for (int i = 0; i < buffer.capacity(); i++)
            {
                byte b = (byte) (i % 256);
                buffer.put(b);
                checksum += b;
            }

            buffer.flip();

            System.out.println("Writing: 128K of data, waiting for checksum " + checksum);
            nuProcess.writeStdin(buffer);
        }

        @Override
        public void onStdout(ByteBuffer buffer)
        {
            if (buffer == null)
            {
                return;
            }

            while (buffer.hasRemaining())
            {
                checksum2 += buffer.get();
            }

            System.out.println("Reading.  Current checksum " + checksum2);
            if (checksum2 == checksum)
            {
                System.out.println("Checksums matched, exiting.");
                nuProcess.closeStdin();
            }
        }
    }
}
