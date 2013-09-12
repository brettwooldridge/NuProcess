package org.nuprocess.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Test;

/**
 * This class demonstrates how one might use the conventional java.lang.Process
 * class to run 20,000 processes (in batches of 500, for 40 iterations).  It is
 * used as kind of a benchmark to compare to the NuProcess method of accomplishing
 * the same (see the NuSchool example).
 *
 * @author Brett Wooldridge
 */
public class OldSchool
{
    private static final int PROCESSES = 500;
    private static volatile CyclicBarrier startBarrier;

    @Test
    public void lotOfProcesses() throws Exception
    {
        ThreadPoolExecutor outExecutor = new ThreadPoolExecutor(PROCESSES, PROCESSES, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        ThreadPoolExecutor inExecutor = new ThreadPoolExecutor(PROCESSES, PROCESSES, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        
        String command = "/bin/cat";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
        {
            command = "src\\test\\java\\org\\nuprocess\\cat.exe";
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        for (int times = 0; times < 10; times++)
        {
            
            Process[] processes = new Process[PROCESSES];
            InPumper[] inPumpers = new InPumper[PROCESSES];
            OutPumper[] outPumpers = new OutPumper[PROCESSES];
    
            startBarrier = new CyclicBarrier(PROCESSES);
            for (int i = 0; i < PROCESSES; i++)
            {
                Process process = pb.start();
                processes[i] = process;
                
                outPumpers[i] = new OutPumper(new BufferedInputStream(process.getInputStream(), 65536));
                inPumpers[i] = new InPumper(new BufferedOutputStream(process.getOutputStream(), 65536));

                outExecutor.execute(outPumpers[i]);
                inExecutor.execute(inPumpers[i]);
            }
    
            for (Process process : processes)
            {
                Assert.assertEquals("Exit code mismatch", 0, process.waitFor());
            }

            for (OutPumper pumper : outPumpers)
            {
                Assert.assertEquals("Adler32 mismatch between written and read", 4237270634l, pumper.getAdler());
            }
        }
    }

    public static class InPumper implements Runnable
    {
        private static final byte[] bytes;
        private OutputStream outputStream;

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

        public InPumper(OutputStream outputStream)
        {
            this.outputStream = outputStream;
        }

        @Override
        public void run()
        {
            try
            {
                startBarrier.await();
                for (int i = 0; i < 100; i++)
                {
                    outputStream.write(bytes);
                }

                outputStream.close();
            }
            catch (Exception e)
            {
                System.err.println(e);
                return;
            }
        }
    }

    public static class OutPumper implements Runnable
    {
        private InputStream inputStream;
        private Adler32 readAdler32;

        OutPumper(InputStream inputStream)
        {
            this.inputStream = inputStream;

            this.readAdler32 = new Adler32();
        }

        @Override
        public void run()
        {
            try
            {
                byte[] buf = new byte[65536];
                while (true)
                {
                    int rc = inputStream.read(buf);
                    if (rc == -1)
                    {
                        break;
                    }

                    readAdler32.update(buf, 0, rc);
                }

                inputStream.close();
            }
            catch (Exception e)
            {
                System.err.println(e);
                return;
            }
        }
        
        long getAdler()
        {
            return readAdler32.getValue();
        }
    }
}
