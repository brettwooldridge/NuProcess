package org.nuprocess.example;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Test;
import org.nuprocess.NuAbstractProcessHandler;
import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessBuilder;

/**
 * This class demonstrates how one might use the NuProcess classes to run 20,000
 * processes (in batches of 500, for 40 iterations).  It is used as kind of a 
 * benchmark to compare to the conventional method of accomplishing the same (see
 * the OldSchool example).
 *
 * @author Brett Wooldridge
 */
public class NuSchool
{
    @Test
    public void lotOfProcesses()
    {
        for (int times = 0; times < 40; times++)
        {
            Semaphore[] semaphores = new Semaphore[500];
            LottaProcessHandler[] handlers = new LottaProcessHandler[500];
    
            for (int i = 0; i < semaphores.length; i++)
            {
                semaphores[i] = new Semaphore(0);
                handlers[i] = new LottaProcessHandler(semaphores[i]);
                NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"), handlers[i]);
                pb.start();
            }
    
            for (Semaphore sem : semaphores)
            {
                sem.acquireUninterruptibly();
            }
            
            for (LottaProcessHandler handler : handlers)
            {
                Assert.assertEquals("Adler32 mismatch between written and read", 593609473, handler.getAdler());
                Assert.assertEquals("Exit code mismatch", 0, handler.getExitCode());
            }
        }
    }

    private static class LottaProcessHandler extends NuAbstractProcessHandler
    {
        private static final byte[] bytes;

        private NuProcess nuProcess;
        private int writes;
        private int exitCode;
        private Semaphore semaphore;

        private Adler32 readAdler32;
        
        static
        {
            // Create 600K of data.
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < 6000; i++)
            {
                sb.append("1234567890");
            }
            bytes = sb.toString().getBytes();
        }

        LottaProcessHandler(Semaphore semaphore)
        {
            this.semaphore = semaphore;

            this.readAdler32 = new Adler32();
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

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            readAdler32.update(bytes);
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer)
        {
            buffer.put(bytes);
            buffer.flip();
            boolean more = (++writes < 10);
            if (!more)
            {
                //nuProcess.closeStdin(false);
            }
            return more;
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
