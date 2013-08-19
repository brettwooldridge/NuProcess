package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.nuprocess.NuProcess;
import org.nuprocess.windows.NuKernel32.OVERLAPPED;

import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;

public final class ProcessCompletions implements Runnable
{
    private static final NuKernel32 KERNEL32 = NuKernel32.INSTANCE;
    private static final int PROCESSOR_THREADS;

    private static HANDLE ioCompletionPort;

    private Map<Integer, WindowsProcess> fildesToProcessMap;

    private CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    static
    {
        PROCESSOR_THREADS = Integer.getInteger("org.nuprocess.threads",
                                            Boolean.getBoolean("org.nuprocess.threadsEqualCores") ? Runtime.getRuntime().availableProcessors() : 1);
    }

    public ProcessCompletions()
    {
        fildesToProcessMap = new ConcurrentHashMap<Integer, WindowsProcess>();
        isRunning = new AtomicBoolean();
    }

    /**
     * The primary run loop of the kqueue event processor.
     */
    @Override
    public void run()
    {
        try
        {
            startBarrier.await();

            checkPortCreated();

            do
            {
                process();
            }
            while (true); //!isRunning.compareAndSet(pidToProcessMap.isEmpty(), false));
            //isRunning.set(false);
        }
        catch (Exception e)
        {
            // TODO: how to handle this error?
            isRunning.set(false);
        }
    }
    
    public void process()
    {
        IntByReference numberOfBytes = new IntByReference();
        ULONG_PTRByReference completionKey = new ULONG_PTRByReference();
        OVERLAPPED.ByReference lpOverlapped = new OVERLAPPED.ByReference();
        if (!KERNEL32.GetQueuedCompletionStatus(ioCompletionPort, numberOfBytes, completionKey, lpOverlapped, NuKernel32.INFINITE /*dwMilliseconds*/))
        {
            if (lpOverlapped.getPointer() == null)
            {
                // error
            }
        }

        int read = numberOfBytes.getValue();
        //read = lpOverlapped.InternalHigh.intValue();
        int key = (int) completionKey.getValue().longValue();
        WindowsProcess process = fildesToProcessMap.get(key);
        if (process != null)
        {
            ByteBuffer buffer = process.getOutBuffer();
            buffer.limit(read);
            buffer.position(0);
            byte[] bytes = new byte[read];
            buffer.get(bytes);
            buffer.clear();
            String foo = new String(bytes);
            System.out.print(foo);
            com.sun.jna.platform.win32.WinBase.OVERLAPPED overlapped = new com.sun.jna.platform.win32.WinBase.OVERLAPPED();

            boolean isOverlapped = false;
            if (!KERNEL32.ReadFile(process.getStdout(), process.getOutBuffer(), NuProcess.BUFFER_CAPACITY, numberOfBytes, overlapped))
            {
                if (KERNEL32.GetLastError() != NuKernel32.ERROR_IO_PENDING)
                {
                    // Some other error occurred reading the file
                    System.err.println("Some other error occurred reading the file");
                    return;
                }
                else
                {
                    isOverlapped = true;
                }
            }
            else
            {
                // Operation completed immediately
                isOverlapped = false;
                // Number of bytes read

                read = numberOfBytes.getValue();
                buffer = process.getOutBuffer();
                buffer.limit(read);
                buffer.position(0);
                bytes = new byte[read];
                buffer.get(bytes);
                buffer.clear();
                foo = new String(bytes);
                System.out.print(foo);
            }
            
            if (isOverlapped)
            {
                int lastError = KERNEL32.GetLastError();
                if (lastError != NuKernel32.ERROR_IO_PENDING)
                {
                    System.err.println("Expected ERROR_IO_PENDING");
                }
            }
            return;
        }

        return;
    }

    /**
     * Get the CyclicBarrier that this thread should join, along with the OsxProcess
     * thread that is starting this processor.  Used to cause the OsxProcess to wait
     * until the processor is up and running before returning from start() to the
     * user.
     *
     * @param processorRunning a CyclicBarrier to join
     */
    public CyclicBarrier getSpawnBarrier()
    {
        startBarrier = new CyclicBarrier(2);
        return startBarrier;
    }

    /**
     * @return
     */
    public boolean checkAndSetRunning()
    {
        return isRunning.compareAndSet(false, true);
    }
    
    public void requeueRead(HANDLE stdin)
    {
        HANDLE completionPort = KERNEL32.CreateIoCompletionPort(stdin, ioCompletionPort, new ULONG_PTR(7), PROCESSOR_THREADS);
        if (!ioCompletionPort.equals(completionPort))
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
        }
        
    }

    public void registerProcess(WindowsProcess process)
    {
        checkPortCreated();

        HANDLE completionPort1 = KERNEL32.CreateIoCompletionPort(process.getStdout(), ioCompletionPort, new ULONG_PTR(process.getStdoutKey()), PROCESSOR_THREADS);
        if (!ioCompletionPort.equals(completionPort1))
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
        }

        HANDLE completionPort2 = KERNEL32.CreateIoCompletionPort(process.getStderr(), ioCompletionPort, new ULONG_PTR(process.getStderrKey()), PROCESSOR_THREADS);
        if (!ioCompletionPort.equals(completionPort2))
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
        }

        fildesToProcessMap.put(process.getStdoutKey(), process);
        fildesToProcessMap.put(process.getStderrKey(), process);

        KERNEL32.ReadFile(process.getStdout(), process.getOutBuffer(), NuProcess.BUFFER_CAPACITY, null, new com.sun.jna.platform.win32.WinBase.OVERLAPPED());
        int lastError = KERNEL32.GetLastError();
        if (lastError != NuKernel32.ERROR_IO_PENDING)
        {
            System.err.println("Expected ERROR_IO_PENDING");
        }
    }

    private void readFile()
    {
        
    }

    private void checkPortCreated()
    {
        synchronized (ProcessCompletions.class)
        {
            if (ioCompletionPort == null)
            {
                ioCompletionPort = KERNEL32.CreateIoCompletionPort(NuKernel32.INVALID_HANDLE_VALUE, null, new ULONG_PTR(0), PROCESSOR_THREADS);
                if (ioCompletionPort == null)
                {
                    throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
                }
            }
        }
    }
}
