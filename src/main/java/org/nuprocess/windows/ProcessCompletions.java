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

package org.nuprocess.windows;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.nuprocess.NuProcess;
import org.nuprocess.windows.WindowsProcess.PipeBundle;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

public final class ProcessCompletions implements Runnable
{
    private static final int DEADPOOL_POLL_INTERVAL;
    private static final int LINGER_ITERATIONS;
    private static final int STDOUT = 0;
    private static final int STDERR = 1;

    private HANDLE ioCompletionPort;

    private List<WindowsProcess> deadPool;
    private BlockingQueue<WindowsProcess> pendingPool;
    private BlockingQueue<WindowsProcess> wantsWrite;

    private Map<Long, WindowsProcess> completionKeyToProcessMap;

    private volatile CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;
    private IntByReference numberOfBytes;
    private ULONG_PTRByReference completionKey;
    private PointerByReference lpOverlapped;

    static
    {
        int lingerTimeMs = Math.max(1000, Integer.getInteger("org.nuprocess.lingerTimeMs", 2500));

        DEADPOOL_POLL_INTERVAL = Math.min(lingerTimeMs, Math.max(50, Integer.getInteger("org.nuprocess.deadPoolPollMs", 50)));
        
        LINGER_ITERATIONS = lingerTimeMs / DEADPOOL_POLL_INTERVAL;
    }

    public ProcessCompletions()
    {
        completionKeyToProcessMap = new HashMap<Long, WindowsProcess>();
        pendingPool = new LinkedBlockingQueue<WindowsProcess>();
        wantsWrite = new LinkedBlockingQueue<WindowsProcess>();
        deadPool = new LinkedList<WindowsProcess>();
        isRunning = new AtomicBoolean();

        numberOfBytes = new IntByReference();
        completionKey = new ULONG_PTRByReference();
        lpOverlapped = new PointerByReference();

        initCompletionPort();
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

            int idleCount = 0;
            while (!isRunning.compareAndSet(idleCount > LINGER_ITERATIONS && deadPool.isEmpty() && completionKeyToProcessMap.isEmpty(), false))
            {
                idleCount = process() ? 0 : (idleCount + 1);
            }
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
            int status = NuKernel32.GetQueuedCompletionStatus(ioCompletionPort, numberOfBytes, completionKey, lpOverlapped, DEADPOOL_POLL_INTERVAL);
            if (status == 0 && lpOverlapped.getValue() == null) // timeout
            {
                checkWaitWrites();
                checkPendingPool();
                return false;
            }
    
            long key = completionKey.getValue().longValue();
            // explicit wake up by us to process pending want writes and registrations
            if (key == 0)
            {
                checkWaitWrites();
                checkPendingPool();
                return true;
            }

            WindowsProcess process = completionKeyToProcessMap.get(key);
            if (process == null)
            {
                return true;
            }

            int transferred = numberOfBytes.getValue();
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
            else if (process.getStdinPipe().ioCompletionKey == key)
            {
                if (process.writeStdin(transferred))
                {
                    queueWrite(process);
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

            if (process.isSoftExit())
            {
                cleanupProcess(process);
            }
    
            return true;
        }
        finally
        {
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
        try
        {
            wantsWrite.put(process);
            NuKernel32.PostQueuedCompletionStatus(ioCompletionPort, 0, new ULONG_PTR(0), null);
        }
        catch (InterruptedException e)
        {
            return;
        }
    }

    public void registerProcess(WindowsProcess process)
    {
        try
        {
            pendingPool.put(process);
            NuKernel32.PostQueuedCompletionStatus(ioCompletionPort, 0, new ULONG_PTR(0), null);
        }
        catch (InterruptedException e)
        {
            return;
        }
    }

    private void queueWrite(WindowsProcess process)
    {
        final PipeBundle stdinPipe = process.getStdinPipe();

        if (!stdinPipe.registered)
        {
            HANDLE completionPort = NuKernel32.CreateIoCompletionPort(stdinPipe.pipeHandle, ioCompletionPort,
                                                                    new ULONG_PTR(stdinPipe.ioCompletionKey),
                                                                    WindowsProcess.PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + Native.getLastError());
            }            

            completionKeyToProcessMap.put(stdinPipe.ioCompletionKey, process);
            stdinPipe.registered = true;
        }

        if (NuKernel32.WriteFile(stdinPipe.pipeHandle, stdinPipe.bufferPointer, 0, null, stdinPipe.overlapped) == 0
            && Native.getLastError() != WinNT.ERROR_IO_PENDING)
        {
            process.stdinClose();
        }
    }

    private void queueRead(WindowsProcess process, WindowsProcess.PipeBundle pipe, int stdX)
    {
        if (NuKernel32.ReadFile(pipe.pipeHandle, pipe.bufferPointer, NuProcess.BUFFER_CAPACITY, null, pipe.overlapped) == 0)
        {
            int lastError = Native.getLastError();
            switch (lastError)
            {
            case WinNT.ERROR_SUCCESS:
            case WinNT.ERROR_IO_PENDING:
                break;
            case WinNT.ERROR_BROKEN_PIPE:
            case WinNT.ERROR_PIPE_NOT_CONNECTED:
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
    }

    private void checkPendingPool()
    {
        WindowsProcess process;
        while ((process = pendingPool.poll()) != null)
        {
            HANDLE completionPort1 = NuKernel32.CreateIoCompletionPort(process.getStdoutPipe().pipeHandle, ioCompletionPort,
                                                                     new ULONG_PTR(process.getStdoutPipe().ioCompletionKey),
                                                                     WindowsProcess.PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort1))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " +Native.getLastError());
            }

            HANDLE completionPort2 = NuKernel32.CreateIoCompletionPort(process.getStderrPipe().pipeHandle, ioCompletionPort,
                                                                     new ULONG_PTR(process.getStderrPipe().ioCompletionKey),
                                                                     WindowsProcess.PROCESSOR_THREADS);
            if (!ioCompletionPort.equals(completionPort2))
            {
                throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + Native.getLastError());
            }

            completionKeyToProcessMap.put(process.getStdoutPipe().ioCompletionKey, process);
            completionKeyToProcessMap.put(process.getStderrPipe().ioCompletionKey, process);

            queueRead(process, process.getStdoutPipe(), STDOUT);
            queueRead(process, process.getStderrPipe(), STDERR);
        }
    }

    private void checkWaitWrites()
    {
        WindowsProcess process;
        while ((process = wantsWrite.poll()) != null)
        {
            queueWrite(process);    
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
            if (NuKernel32.GetExitCodeProcess(process.getPid(), exitCode) && exitCode.getValue() != WinNT.STILL_ACTIVE)
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

        IntByReference exitCode = new IntByReference();
        if (NuKernel32.GetExitCodeProcess(process.getPid(), exitCode) && exitCode.getValue() != WinNT.STILL_ACTIVE)
        {
            process.onExit(exitCode.getValue());
        }
        else
        {
            deadPool.add(process);            
        }
    }

    private void initCompletionPort()
    {
        ioCompletionPort = NuKernel32.CreateIoCompletionPort(WinNT.INVALID_HANDLE_VALUE, null, new ULONG_PTR(0), WindowsProcess.PROCESSOR_THREADS);
        if (ioCompletionPort == null)
        {
            throw new RuntimeException("CreateIoCompletionPort() failed, error code: " + Native.getLastError());
        }
    }
}
