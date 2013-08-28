package org.nuprocess.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuprocess.RunOnlyOnUnix;

@RunWith(value=RunOnlyOnUnix.class)
public class OldSchool
{
    @Test
    public void lotOfProcesses() throws Exception
    {
        ThreadPoolExecutor outExecutor = new ThreadPoolExecutor(50, 50, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        ThreadPoolExecutor inExecutor = new ThreadPoolExecutor(50, 50, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        
        ProcessBuilder pb = new ProcessBuilder("/bin/cat");
        pb.redirectErrorStream(true);

        for (int times = 0; times < 100; times++)
        {
            Process[] processes = new Process[50];
            InPumper[] inPumpers = new InPumper[50];
            OutPumper[] outPumpers = new OutPumper[50];
    
            for (int i = 0; i < processes.length; i++)
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
                Assert.assertEquals("Adler32 mismatch between written and read", 593609473, pumper.getAdler());
            }
        }
    }

    public static class InPumper implements Runnable
    {
        private OutputStream outputStream;
        private StringBuffer sb;

        public InPumper(OutputStream outputStream)
        {
            this.outputStream = outputStream;
            this.sb = new StringBuffer();

            for (int i = 0; i < 6000; i++)
            {
                sb.append("1234567890");
            }
        }

        @Override
        public void run()
        {
            try
            {
                for (int i = 0; i < 10; i++)
                {
                    byte[] bytes = sb.toString().getBytes();
                    outputStream.write(bytes);
                }

                outputStream.close();
            }
            catch (Exception e)
            {
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
                return;
            }
        }
        
        long getAdler()
        {
            return readAdler32.getValue();
        }
    }
}
