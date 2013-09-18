package com.zaxxer.nuprocess.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;

/**
 * This class demonstrates how one might use the conventional java.lang.Process
 * class to run 5000 processes (in batches of 500, for 10 iterations).  It is
 * used as kind of a benchmark to compare to the NuProcess method of accomplishing
 * the same (see the NuSchool example).
 *
 * Execution notes:
 * 
 * Linux (CentOS)
 *    In order to run this test, java was run with the following parameters:
 *       -Xmx2048m -Xss256k
 * 
 *    The following had to be added to /etc/security/limits.conf:
 *       #domain         type    item           value
 *
 *        [user]         soft    nofile         4096
 *        [user]         hard    nofile         8192
 *        [user]         soft    nproc          4096
 *        [user]         soft    nproc          4096
 *    where [user] is the username of the executing user (you).
 *
 *    The following change was made to /etc/security/limits.d/90-nproc.conf:
 *      *          soft    nproc     1024
 *    was changed to:
 *      *          soft    nproc     8024
 *
 * @author Brett Wooldridge
 */
public class OldSchool
{
    private static volatile CyclicBarrier startBarrier;

    public static void main(String... args)
    {
    	if (args.length < 1)
    	{
    		System.err.println("Usage: java com.zaxxer.nuprocess.example.OldSchool <num of processes>");
    		System.exit(0);
    	}

    	int PROCESSES = Integer.valueOf(args[0]);

    	ThreadPoolExecutor outExecutor = new ThreadPoolExecutor(PROCESSES, PROCESSES, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        ThreadPoolExecutor inExecutor = new ThreadPoolExecutor(PROCESSES, PROCESSES, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        
        String command = "/bin/cat";
        if (System.getProperty("os.name").toLowerCase().contains("win"))
        {
            command = "src\\test\\java\\org\\nuprocess\\cat.exe";
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        long start = System.currentTimeMillis();
        long maxFreeMem = 0;

        for (int times = 0; times < 10; times++)
        {
            
            Process[] processes = new Process[PROCESSES];
            InPumper[] inPumpers = new InPumper[PROCESSES];
            OutPumper[] outPumpers = new OutPumper[PROCESSES];
    
            startBarrier = new CyclicBarrier(PROCESSES);
            try
            {
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
                    maxFreeMem = Math.max(maxFreeMem, Runtime.getRuntime().freeMemory());
	                if (process.waitFor() != 0)
	            	{
	                   System.err.println("Exit code not zero (0)");
	                   System.exit(-1);
	            	}
	            }
	
	            for (OutPumper pumper : outPumpers)
	            {
	            	if (pumper.getAdler() != 4237270634l)
	            	{
	                    System.err.println("Adler32 mismatch between written and read");
	                    System.exit(-1);
	            	}
	            }
            }
            catch (Exception e)
            {
            	e.printStackTrace(System.err);
            	System.exit(-1);
            }
        }

        System.out.printf("Maximum memory used: %d\n", Runtime.getRuntime().totalMemory() - maxFreeMem);
        System.out.printf("Total execution time (ms): %d\n", (System.currentTimeMillis() - start));
        System.exit(0);
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
