package org.nuprocess.windows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
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
    private static final int LINGER_ITERATIONS;
    private static final int STDOUT = 0;
    private static final int STDERR = 1;

    private HANDLE ioCompletionPort;

    private List<WindowsProcess> deadPool;
    private BlockingQueue<WindowsProcess> pendingPool;
    private BlockingQueue<WindowsProcess> wantsWrite;

    private Map<Integer, WindowsProcess> completionKeyToProcessMap;

    private CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    static
    {
        int lingerTimeMs = Math.max(1000, Integer.getInteger("org.nuprocess.lingerTimeMs", 2500));

        DEADPOOL_POLL_INTERVAL = Math.min(lingerTimeMs, Math.max(100, Integer.getInteger("org.nuprocess.deadPoolPollMs", 250)));
        
        LINGER_ITERATIONS = lingerTimeMs / DEADPOOL_POLL_INTERVAL;
    }

    public ProcessCompletions()
    {
        completionKeyToProcessMap = new ConcurrentHashMap<Integer, WindowsProcess>();
        pendingPool = new LinkedBlockingQueue<WindowsProcess>();
        deadPool = new LinkedList<WindowsProcess>();
        wantsWrite = new LinkedBlockingQueue<WindowsProcess>();
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
            HANDLE completionPort = initCompletionPort();
            
            startBarrier.await();

            int idleCount = 0;
            do
            {
                if (process())
                {
                    idleCount = 0;
                }
                else
                {
                    idleCount++;
                }
            }
            while (!isRunning.compareAndSet(completionKeyToProcessMap.isEmpty() && deadPool.isEmpty() && idleCount > LINGER_ITERATIONS, false));

            destroyCompletionPort(completionPort);
        }
        catch (Exception e)
        {
            // TODO: how to handle this error?
            e.printStackTrace();
            isRunning.set(false);
        }
    }
    
    public boolean process()
    {
        try
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
                    return false;
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
                        process.readStdout(transferred);
                        queueRead(process, process.getStdoutPipe(), STDOUT);
                    }
                    else
                    {
                        process.readStdout(-1);
                    }
                }
                else if (process.getStderrPipe().ioCompletionKey == key)
                {
                    if (transferred > 0)
                    {
                        process.readStderr(transferred);
                        queueRead(process, process.getStderrPipe(), STDERR);
                    }
                    else
                    {
                        process.readStderr(-1);
                    }
                }
                else if (process.getStdinPipe().ioCompletionKey == key)
                {
                    if (process.writeStdin(transferred))
                    {
                        queueWrite(process);
                    }
                }
    
                if (process.isSoftExit())
                {
                    cleanupProcess(process);
                    deadPool.add(process);
                }
            }
    
            return true;
        }
        finally
        {
            checkWaitWrites();
            checkPendingPool();
            checkDeadPool();
        }
    }

    /**
     * Get the CyclicBarrier that this thread should join, along with the OsxProcess
     * thread that is starting this processor.  Used to cause the OsxProcess to wait
     * until the processor is up and running before returning from start() to the
     * user.
     *
     * @param processorRunning a CyclicBarrier to join
     */
    CyclicBarrier getSpawnBarrier()
    {
        startBarrier = new CyclicBarrier(2);
        return startBarrier;
    }

    /**
     * @return
     */
    boolean checkAndSetRunning()
    {
        return isRunning.compareAndSet(false, true);
    }
    
    void wantWrite(WindowsProcess process)
    {
        wantsWrite.add(process);
    }

    public void registerProcess(WindowsProcess process)
    {
        pendingPool.add(process);
    }

    private void queueWrite(WindowsProcess process)
    {
        if (!completionKeyToProcessMap.containsKey(process.getStdinPipe().ioCompletionKey))
        {
            HANDLE completionPort = KERNEL32.CreateIoCompletionPort(process.getStdinPipe().pipeHandle, ioCompletionPort,
                                                                    new ULONG_PTR(process.getStdinPipe().ioCompletionKey), WindowsProcess.PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
            }            

            completionKeyToProcessMap.put(process.getStdinPipe().ioCompletionKey, process);
        }

        KERNEL32.WriteFile(process.getStdinPipe().pipeHandle, process.getStdinPipe().buffer, 0, null, process.getStdinPipe().overlapped);
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
                process.readStdout(-1 /*closed*/);
            }
            else
            {
                process.readStderr(-1 /*closed*/);
            }
            break;
        default:
            System.err.println("Some other error occurred reading the pipe: " + lastError);
            break;
        }
    }

    private void checkPendingPool()
    {
        if (pendingPool.isEmpty())
        {
            return;
        }

        ArrayList<WindowsProcess> list = new ArrayList<>(pendingPool.size());
        pendingPool.drainTo(list);

        for (WindowsProcess process : list)
        {
            HANDLE completionPort1 = KERNEL32.CreateIoCompletionPort(process.getStdoutPipe().pipeHandle, ioCompletionPort,
                                                                     new ULONG_PTR(process.getStdoutPipe().ioCompletionKey),
                                                                     WindowsProcess.PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort1))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
            }

            HANDLE completionPort2 = KERNEL32.CreateIoCompletionPort(process.getStderrPipe().pipeHandle, ioCompletionPort,
                                                                     new ULONG_PTR(process.getStderrPipe().ioCompletionKey),
                                                                     WindowsProcess.PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort2))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
            }

            completionKeyToProcessMap.put(process.getStdoutPipe().ioCompletionKey, process);
            completionKeyToProcessMap.put(process.getStderrPipe().ioCompletionKey, process);

            queueRead(process, process.getStdoutPipe(), STDOUT);
            queueRead(process, process.getStderrPipe(), STDERR);
        }
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

    private void checkWaitWrites()
    {
        if (wantsWrite.isEmpty())
        {
            return;
        }

        ArrayList<WindowsProcess> list = new ArrayList<>(wantsWrite.size());
        wantsWrite.drainTo(list);
        for (WindowsProcess process : list)
        {
            queueWrite(process);
        }
    }

    private void cleanupProcess(WindowsProcess process)
    {
        completionKeyToProcessMap.remove(process.getStdinPipe().ioCompletionKey);
        completionKeyToProcessMap.remove(process.getStdoutPipe().ioCompletionKey);
        completionKeyToProcessMap.remove(process.getStderrPipe().ioCompletionKey);
    }

    private HANDLE initCompletionPort()
    {
        ioCompletionPort = KERNEL32.CreateIoCompletionPort(NuKernel32.INVALID_HANDLE_VALUE, null, new ULONG_PTR(0), WindowsProcess.PROCESSOR_THREADS);
        if (ioCompletionPort == null)
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + KERNEL32.GetLastError());
        }

        return ioCompletionPort;
    }

    private void destroyCompletionPort(HANDLE completionPort)
    {
        if (completionPort != null)
        {
            KERNEL32.CloseHandle(completionPort);
        }
    }
}
