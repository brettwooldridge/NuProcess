package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.nuprocess.NuProcess;

import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

public final class ProcessCompletions implements Runnable
{
    private static final NuKernel32 KERNEL32 = NuKernel32.INSTANCE;
    private static final int DEADPOOL_POLL_INTERVAL;
    private static final int PROCESSOR_THREADS;
    private static final int STDOUT = 0;
    private static final int STDERR = 1;

    private HANDLE ioCompletionPort;
    private List<WindowsProcess> deadPool;

    private Map<Integer, WindowsProcess> completionKeyToProcessMap;

    private CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    static
    {
        PROCESSOR_THREADS = Integer.getInteger("org.nuprocess.threads",
                                            Boolean.getBoolean("org.nuprocess.threadsEqualCores") ? Runtime.getRuntime().availableProcessors() : 1);
        DEADPOOL_POLL_INTERVAL = Integer.getInteger("org.nuprocess.deadPoolPollMs", 250);
    }

    public ProcessCompletions()
    {
        completionKeyToProcessMap = new ConcurrentHashMap<Integer, WindowsProcess>();
        deadPool = new LinkedList<WindowsProcess>();
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

            synchronized (this)
            {
                checkPortCreated();
    
                do
                {
                    process();
                }
                while (!isRunning.compareAndSet(completionKeyToProcessMap.isEmpty() && deadPool.isEmpty(), false));

                KERNEL32.CloseHandle(ioCompletionPort);
                ioCompletionPort = null;
                isRunning.set(false);
            }
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
        PointerByReference lpOverlapped = new PointerByReference();
        boolean status = KERNEL32.GetQueuedCompletionStatus(ioCompletionPort, numberOfBytes, completionKey, lpOverlapped, DEADPOOL_POLL_INTERVAL);
        int key = (int) completionKey.getValue().longValue();
        if (!status)
        {
            int lastError = KERNEL32.GetLastError();
            if (lastError == Kernel32.WAIT_TIMEOUT)
            {
                checkDeadPool();
                return;
            }

            if (lastError != Kernel32.ERROR_BROKEN_PIPE)
            {
                System.err.println("Error " + lastError);
            }
        }

        int transferred = numberOfBytes.getValue();
        WindowsProcess process = completionKeyToProcessMap.get(key);
        if (process != null)
        {
            if (process.getStdoutPipe().ioCompletionKey == key)
            {
                if (transferred > 0)
                {
                    processInput(process, process.getStdoutPipe(), transferred, STDOUT);
                    queueRead(process, process.getStdoutPipe(), STDOUT);
                }
                else
                {
                    process.readStdout(true);
                }
            }
            else if (process.getStderrPipe().ioCompletionKey == key)
            {
                if (transferred > 0)
                {
                    processInput(process, process.getStderrPipe(), transferred, STDERR);
                    queueRead(process, process.getStderrPipe(), STDERR);
                }
                else
                {
                    process.readStderr(true);
                }
            }
            else if (process.getStdinPipe().ioCompletionKey == key)
            {
                if (processOutput(process, transferred))
                {
                    requeueRead(process);
                }
            }

            if (process.isSoftExit())
            {
                cleanupProcess(process);
                IntByReference exitCode = new IntByReference();
                if (KERNEL32.GetExitCodeProcess(process.getPid(), exitCode) && exitCode.getValue() != Kernel32.STILL_ACTIVE)
                {
                    process.onExit(exitCode.getValue());
                }
                else
                {
                    deadPool.add(process);
                }
            }
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
    
    public void requeueRead(WindowsProcess process)
    {
        if (!completionKeyToProcessMap.containsKey(process.getStdinPipe().ioCompletionKey))
        {
            HANDLE completionPort = KERNEL32.CreateIoCompletionPort(process.getStdinPipe().pipeHandle, ioCompletionPort,
                                                                    new ULONG_PTR(process.getStdinPipe().ioCompletionKey), PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
            }            

            completionKeyToProcessMap.put(process.getStdinPipe().ioCompletionKey, process);
        }

        KERNEL32.WriteFile(process.getStdinPipe().pipeHandle, process.getStdinPipe().buffer, 0, null, process.getStdinPipe().overlapped);
    }

    public void registerProcess(WindowsProcess process)
    {
        checkPortCreated();

        HANDLE completionPort1 = KERNEL32.CreateIoCompletionPort(process.getStdoutPipe().pipeHandle, ioCompletionPort,
                                                                 new ULONG_PTR(process.getStdoutPipe().ioCompletionKey),
                                                                 PROCESSOR_THREADS);
        if (!ioCompletionPort.equals(completionPort1))
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
        }

        HANDLE completionPort2 = KERNEL32.CreateIoCompletionPort(process.getStderrPipe().pipeHandle, ioCompletionPort,
                                                                 new ULONG_PTR(process.getStderrPipe().ioCompletionKey),
                                                                 PROCESSOR_THREADS);
        if (!ioCompletionPort.equals(completionPort2))
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
        }

        completionKeyToProcessMap.put(process.getStdoutPipe().ioCompletionKey, process);
        completionKeyToProcessMap.put(process.getStderrPipe().ioCompletionKey, process);

        queueRead(process, process.getStdoutPipe(), STDOUT);
        queueRead(process, process.getStderrPipe(), STDERR);
    }

    private void queueRead(WindowsProcess process, WindowsProcess.PipeBundle pipe, int stdX)
    {
        KERNEL32.ReadFile(pipe.pipeHandle, pipe.buffer, NuProcess.BUFFER_CAPACITY, null, pipe.overlapped);
        int lastError = KERNEL32.GetLastError();
        switch (lastError)
        {
        case Kernel32.ERROR_SUCCESS:
        case Kernel32.ERROR_IO_PENDING:
            break;
        case Kernel32.ERROR_BROKEN_PIPE:
            if (stdX == STDOUT)
            {
                process.readStdout(true /*closed*/);
            }
            else
            {
                process.readStderr(true /*closed*/);
            }
            break;
        default:
            System.err.println("Some other error occurred reading the pipe: " + lastError);
            break;
        }
    }

    private void processInput(WindowsProcess process, WindowsProcess.PipeBundle pipe, int read, int stdX)
    {
        pipe.buffer.position(0);
        pipe.buffer.limit(read);

        if (stdX == STDOUT)
        {
            process.readStdout(false);
        }
        else
        {
            process.readStderr(false);
        }

        pipe.buffer.clear();
    }

    private boolean processOutput(WindowsProcess process, int transferred)
    {
        
        ByteBuffer buffer = process.getStdinPipe().buffer;
        buffer.position(buffer.position() + transferred);
        if (buffer.position() < buffer.limit())
        {
            KERNEL32.WriteFile(process.getStdinPipe().pipeHandle, buffer, buffer.remaining(), null, process.getStdinPipe().overlapped);

            return false;
        }

        if (process.userWantsWrite.compareAndSet(true, false))
        {
            buffer.clear();
            return process.writeStdin();
        }

        return false;
    }

    private void checkDeadPool()
    {
        if (deadPool.isEmpty())
        {
            return;
        }

        IntByReference exitCode = new IntByReference();
        Iterator<WindowsProcess> iterator = deadPool.iterator();
        while (iterator.hasNext())
        {
            WindowsProcess process = iterator.next();
            if (KERNEL32.GetExitCodeProcess(process.getPid(), exitCode) && exitCode.getValue() != Kernel32.STILL_ACTIVE)
            {
                iterator.remove();
                process.onExit(exitCode.getValue());
            }
        }
    }

    private void cleanupProcess(WindowsProcess process)
    {
        completionKeyToProcessMap.remove(process.getStdinPipe().ioCompletionKey);
        completionKeyToProcessMap.remove(process.getStdoutPipe().ioCompletionKey);
        completionKeyToProcessMap.remove(process.getStderrPipe().ioCompletionKey);
    }

    private void checkPortCreated()
    {
        synchronized (this)
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
