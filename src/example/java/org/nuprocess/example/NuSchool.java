package org.nuprocess.example;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Test;
import org.nuprocess.NuAbstractProcessHandler;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;

/**
 * This class demonstrates how one might use the NuProcess classes to run 5000
 * processes (in batches of 500, for 10 iterations).  It is used as kind of a 
 * benchmark to compare to the conventional method of accomplishing the same
 * (see the OldSchool example).
 *
 * @author Brett Wooldridge
 */
public class NuSchool
{
    private static final int PROCESSES = 500;

    @Test
    public void lotOfProcesses() throws InterruptedException
    {
        String command = "/bin/cat";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
        {
            command = "src\\test\\java\\org\\nuprocess\\cat.exe";
        }

        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList(command));
        for (int times = 0; times < 10; times++)
        {
            NuProcess[] processes = new NuProcess[PROCESSES];
            LottaProcessHandler[] handlers = new LottaProcessHandler[PROCESSES];
    
            for (int i = 0; i < PROCESSES; i++)
            {
                handlers[i] = new LottaProcessHandler();
                pb.setProcessListener(handlers[i]);
                processes[i] = pb.start();
            }

            // Kick all of the processes to start going
            for (NuProcess process : processes)
            {
                process.wantWrite();
            }

            for (NuProcess process : processes)
            {
                process.waitFor(0, TimeUnit.SECONDS);
            }

            for (LottaProcessHandler handler : handlers)
            {
                Assert.assertEquals("Adler32 mismatch between written and read", 4237270634l, handler.getAdler());
                Assert.assertEquals("Exit code mismatch", 0, handler.getExitCode());
            }
        }
    }

    private static class LottaProcessHandler extends NuAbstractProcessHandler
    {
        private static final int WRITES = 100;
        private static final int LIMIT;
        private static final byte[] bytes;

        private NuProcess nuProcess;
        private int writes;
        private int size;
        private int exitCode;

        private Adler32 readAdler32 = new Adler32();

        static
        {
            // Create 600K of data.
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < 6000; i++)
            {
                sb.append("1234567890");
            }
            bytes = sb.toString().getBytes();
            LIMIT = WRITES * bytes.length;
        }

        @Override
        public void onStart(final NuProcess nuProcess)
        {
            this.nuProcess = nuProcess;
        }

        @Override
        public void onExit(int statusCode)
        {
            exitCode = statusCode;
        }

        @Override
        public void onStdout(ByteBuffer buffer)
        {
            if (buffer == null)
            {
                return;
            }

            size += buffer.remaining();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            readAdler32.update(bytes);
            
            if (size == LIMIT)
            {
                nuProcess.closeStdin();
            }
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer)
        {
            buffer.put(bytes);
            buffer.flip();
            return (++writes < WRITES);
        }

        int getExitCode()
        {
            return exitCode;
        }

        long getAdler()
        {
            return readAdler32.getValue();
        }
    };
}
