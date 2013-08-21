package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;
import org.nuprocess.windows.NuKernel32.OVERLAPPED;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.WinBase.STARTUPINFO;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;

/**
 * @author Brett Wooldridge
 */
public final class WindowsProcess implements NuProcess
{
    private static final NuKernel32 KERNEL32 = NuKernel32.INSTANCE;

    private static final boolean IS_SOFTEXIT_DETECTION = true;

    private static final int PROCESSOR_THREADS;
    private static final int BUFFER_SIZE = 4096;

    private static ProcessCompletions[] processors;
    private static int processorRoundRobin;

    private static final AtomicInteger namedPipeCounter;

    private ProcessCompletions myProcessor;
    private NuProcessListener processListener;

    private String[] environment;
    private String[] commands;
    private AtomicInteger exitCode;
    private CountDownLatch exitPending;

    AtomicBoolean userWantsWrite;

    private PipeBundle stdinPipe;
    private PipeBundle stdoutPipe;
    private PipeBundle stderrPipe;

    private HANDLE hStdinWidow;
    private HANDLE hStdoutWidow;
    private HANDLE hStderrWidow;

    private volatile boolean inClosed;
    private volatile boolean outClosed;
    private volatile boolean errClosed;

    private PROCESS_INFORMATION processInfo;

    static
    {
        namedPipeCounter = new AtomicInteger(100);

        PROCESSOR_THREADS = Integer.getInteger("org.nuprocess.threads",
                                            Boolean.getBoolean("org.nuprocess.threadsEqualCores") ? Runtime.getRuntime().availableProcessors() : 1);

        processors = new ProcessCompletions[PROCESSOR_THREADS];
        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessCompletions();
        }
    }

    public WindowsProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        this.commands = commands.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;
        this.userWantsWrite = new AtomicBoolean();
        this.exitCode = new AtomicInteger();
        this.exitPending = new CountDownLatch(1);

        this.outClosed = true;
        this.errClosed = true;

        this.hStdinWidow = new HANDLE();
        this.hStdoutWidow = new HANDLE();
        this.hStderrWidow = new HANDLE();
    }

    @Override
    public NuProcess start()
    {
        try
        {
            createPipes();

            char[] block = getEnvironment();
            Memory env = new Memory(block.length * 3);
            env.write(0, block, 0, block.length);

            STARTUPINFO startupInfo = new STARTUPINFO();
            startupInfo.clear();
            startupInfo.cb = new DWORD(startupInfo.size());
            startupInfo.hStdInput = hStdinWidow;
            startupInfo.hStdError = hStderrWidow;
            startupInfo.hStdOutput = hStdoutWidow;
            startupInfo.dwFlags = Kernel32.STARTF_USESTDHANDLES;

            processInfo = new PROCESS_INFORMATION();

            DWORD dwCreationFlags = new DWORD(Kernel32.CREATE_NO_WINDOW | Kernel32.CREATE_UNICODE_ENVIRONMENT | Kernel32.CREATE_SUSPENDED);
            if (!KERNEL32.CreateProcessW(null, getCommandLine(), null /*lpProcessAttributes*/, null /*lpThreadAttributes*/, true /*bInheritHandles*/,
                                         dwCreationFlags, env, null /*lpCurrentDirectory*/, startupInfo, processInfo))
            {
                int lastError = KERNEL32.GetLastError();
                throw new RuntimeException("CreateProcessW() failed, error: " + lastError);
            }

            KERNEL32.CloseHandle(hStdinWidow);
            KERNEL32.CloseHandle(hStdoutWidow);
            KERNEL32.CloseHandle(hStderrWidow);

            afterStart();

            registerProcess();

            kickstartProcessors();

            callStart();

            KERNEL32.ResumeThread(processInfo.hThread);
        }
        catch (RuntimeException e)
        {
            onExit(Integer.MIN_VALUE);
        }

        return this;
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        return exitCode.get();
    }

    @Override
    public void wantWrite()
    {
        if (hStdinWidow != null && !WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdoutWidow.getPointer()))
        {
            userWantsWrite.set(true);
            myProcessor.requeueRead(this);
        }
    }

    @Override
    public void stdinClose()
    {
        if (!inClosed)
        {
            KERNEL32.CloseHandle(stdinPipe.pipeHandle);
            inClosed = true;
        }
    }

    @Override
    public void destroy()
    {
    }

    HANDLE getPid()
    {
        return processInfo.hProcess;
    }

    PipeBundle getStdinPipe()
    {
        return stdinPipe;
    }

    PipeBundle getStdoutPipe()
    {
        return stdoutPipe;
    }

    PipeBundle getStderrPipe()
    {
        return stderrPipe;
    }

    void readStdout(boolean closed)
    {
        try
        {
            if (outClosed)
            {
                return;
            }
            else if (closed)
            {
                outClosed = true;
                processListener.onStdout(null);
                return;
            }

            processListener.onStdout(stdoutPipe.buffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    void readStderr(boolean closed)
    {
        try
        {
            if (errClosed)
            {
                return;
            }
            else if (closed)
            {
                errClosed = true;
                processListener.onStderr(null);
                return;
            }

            processListener.onStderr(stderrPipe.buffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    boolean writeStdin()
    {
        ByteBuffer buffer = stdinPipe.buffer;
//        if (buffer.limit() < buffer.capacity())
//        {
//            ByteBuffer slice = buffer.slice();
//            int wrote = LIBC.write(stdin, slice, slice.capacity());
//            if (wrote == -1)
//            {
//                // EOF?
//                return false;
//            }
//
//            buffer.position(buffer.position() + wrote);
//            if (userWantsWrite.compareAndSet(false, false))
//            {
//                return (wrote == slice.capacity() ? false : true);
//            }
//        }

        try
        {
            boolean wantMore = processListener.onStdinReady(buffer);
            userWantsWrite.set(wantMore);
            return wantMore;
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            e.printStackTrace();
            return false;
        }
    }

    public void onExit(int statusCode)
    {
        if (exitPending.getCount() == 0)
        {
            return;
        }

        try
        {
            exitPending.countDown();
            exitCode.set(statusCode);
            processListener.onExit(statusCode);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
        finally
        {
            if (!inClosed)
            {
                KERNEL32.CloseHandle(stdinPipe.pipeHandle);
            }

            KERNEL32.CloseHandle(stdoutPipe.pipeHandle);
            KERNEL32.CloseHandle(stderrPipe.pipeHandle);
            KERNEL32.CloseHandle(processInfo.hThread);
            KERNEL32.CloseHandle(processInfo.hProcess);

            stderrPipe = null;
            stdoutPipe = null;
            stdinPipe = null;
            processListener = null;
        }
    }

    boolean isSoftExit()
    {
        return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
    }

    private void callStart()
    {
        try
        {
            processListener.onStart(this);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    private void createPipes()
    {
        int lastError = 0;

        SECURITY_ATTRIBUTES sattr = new SECURITY_ATTRIBUTES();
        sattr.dwLength = new DWORD(sattr.size());
        sattr.bInheritHandle = true;
        sattr.lpSecurityDescriptor = null;

        int dwOpenMode = NuKernel32.PIPE_ACCESS_INBOUND | NuKernel32.FILE_FLAG_OVERLAPPED;

        /* stdout pipe */
        stdoutPipe = new PipeBundle();
        stdoutPipe.ioCompletionKey = namedPipeCounter.getAndIncrement();
        String pipeName = "\\\\.\\pipe\\NuProcess" + stdoutPipe.ioCompletionKey;
        hStdoutWidow = KERNEL32.CreateNamedPipe(pipeName, dwOpenMode, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                       0 /*nDefaultTimeOut*/, sattr);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdoutWidow.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        stdoutPipe.pipeHandle = KERNEL32.CreateFile(pipeName, Kernel32.GENERIC_READ, Kernel32.FILE_SHARE_READ /*dwShareMode*/, null,
                                          Kernel32.OPEN_EXISTING /*dwCreationDisposition*/, Kernel32.FILE_ATTRIBUTE_NORMAL | Kernel32.FILE_FLAG_OVERLAPPED /*dwFlagsAndAttributes*/,
                                          null /*hTemplateFile*/);
        if (stdoutPipe.pipeHandle == null || WinBase.INVALID_HANDLE_VALUE.getPointer().equals(stdoutPipe.pipeHandle.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        OVERLAPPED overlapped = new OVERLAPPED();
        overlapped.clear();
        overlapped.hEvent = KERNEL32.CreateEvent(null, true, false, null);
        boolean status = KERNEL32.ConnectNamedPipe(hStdoutWidow, overlapped);
        lastError = KERNEL32.GetLastError();
        if (!status && lastError == Kernel32.ERROR_IO_PENDING)
        {
            KERNEL32.WaitForSingleObject(overlapped.hEvent, Kernel32.INFINITE);
            status = KERNEL32.HasOverlappedIoCompleted(overlapped);
        }
        KERNEL32.CloseHandle(overlapped.hEvent);
        if (!status && (lastError != Kernel32.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }

        /* stderr pipe */
        stderrPipe = new PipeBundle();
        stderrPipe.ioCompletionKey = namedPipeCounter.getAndIncrement();
        pipeName = "\\\\.\\pipe\\NuProcess" + stderrPipe.ioCompletionKey;
        hStderrWidow = KERNEL32.CreateNamedPipe(pipeName, dwOpenMode, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                       0 /*nDefaultTimeOut*/, sattr);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStderrWidow.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        stderrPipe.pipeHandle = KERNEL32.CreateFile(pipeName, Kernel32.GENERIC_READ, Kernel32.FILE_SHARE_READ /*dwShareMode*/, null,
                                          Kernel32.OPEN_EXISTING /*dwCreationDisposition*/, Kernel32.FILE_ATTRIBUTE_NORMAL | Kernel32.FILE_FLAG_OVERLAPPED /*dwFlagsAndAttributes*/,
                                          null /*hTemplateFile*/);
        if (stderrPipe.pipeHandle == null || WinBase.INVALID_HANDLE_VALUE.getPointer().equals(stderrPipe.pipeHandle.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        overlapped.clear();
        overlapped.hEvent = KERNEL32.CreateEvent(null, true, false, null);
        status = KERNEL32.ConnectNamedPipe(hStderrWidow, overlapped);
        lastError = KERNEL32.GetLastError();
        if (!status && lastError == Kernel32.ERROR_IO_PENDING)
        {
            KERNEL32.WaitForSingleObject(overlapped.hEvent, Kernel32.INFINITE);
            status = KERNEL32.HasOverlappedIoCompleted(overlapped);
        }
        KERNEL32.CloseHandle(overlapped.hEvent);
        if (!status && (lastError != Kernel32.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }

        /* stdin pipe */
        stdinPipe = new PipeBundle();
        stdinPipe.ioCompletionKey = namedPipeCounter.getAndIncrement();
        dwOpenMode = NuKernel32.PIPE_ACCESS_OUTBOUND | NuKernel32.FILE_FLAG_OVERLAPPED;
        pipeName = "\\\\.\\pipe\\NuProcess" + stdinPipe.ioCompletionKey;
        hStdinWidow = KERNEL32.CreateNamedPipe(pipeName, dwOpenMode, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                       0 /*nDefaultTimeOut*/, sattr);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdinWidow.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        stdinPipe.pipeHandle = KERNEL32.CreateFile(pipeName, Kernel32.GENERIC_WRITE, Kernel32.FILE_SHARE_WRITE /*dwShareMode*/, null,
                                          Kernel32.OPEN_EXISTING /*dwCreationDisposition*/, Kernel32.FILE_ATTRIBUTE_NORMAL | Kernel32.FILE_FLAG_OVERLAPPED /*dwFlagsAndAttributes*/,
                                          null /*hTemplateFile*/);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(stdinPipe.pipeHandle.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        overlapped.clear();
        overlapped.hEvent = KERNEL32.CreateEvent(null, true, false, null);
        status = KERNEL32.ConnectNamedPipe(hStdinWidow, overlapped);
        lastError = KERNEL32.GetLastError();
        if (!status && lastError == Kernel32.ERROR_IO_PENDING)
        {
            KERNEL32.WaitForSingleObject(overlapped.hEvent, Kernel32.INFINITE);
            status = KERNEL32.HasOverlappedIoCompleted(overlapped);
        }
        KERNEL32.CloseHandle(overlapped.hEvent);
        if (!status && (lastError != Kernel32.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }
    }

    private void afterStart()
    {
        commands = null;
        environment = null;

        outClosed = false;
        errClosed = false;

        stdoutPipe.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        stderrPipe.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        stdinPipe.buffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        stdinPipe.buffer.flip();
    }

    private void registerProcess()
    {
        synchronized (this.getClass())
        {
            myProcessor = processors[processorRoundRobin]; 
            myProcessor.registerProcess(this);
            processorRoundRobin = (processorRoundRobin + 1) % processors.length;
        }
    }

    private void kickstartProcessors()
    {
        for (int i = 0; i < processors.length; i++)
        {
            if (processors[i].checkAndSetRunning())
            {
                CyclicBarrier spawnBarrier = processors[i].getSpawnBarrier();

                Thread t = new Thread(processors[i], "ProcessIoCompletion" + i);
                t.setDaemon(true);
                t.start();

                try
                {
                    spawnBarrier.await();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private char[] getCommandLine()
    {
        StringBuilder sb = new StringBuilder();
        if (commands[0].contains(" ") && !commands[0].startsWith("\"") && !commands[0].endsWith("\""))
        {
            commands[0] = "\"" + commands[0].replaceAll("\\\"", "\\\"") + "\"";
        }

        for (String s : commands)
        {
            sb.append(s).append(' ');
        }

        if (sb.length() > 0)
        {
            sb.setLength(sb.length() - 1);
        }

        sb.append((char) 0);

        return sb.toString().toCharArray();
    }

    private char[] getEnvironment()
    {
        Map<String, String> env = new HashMap<String, String>(System.getenv());
        for (String entry : environment)
        {
            int ndx = entry.indexOf('=');
            if (ndx != -1)
            {
                env.put(entry.substring(0, ndx), (ndx < entry.length() ? entry.substring(ndx + 1) : ""));
            }
        }

        return getEnvironmentBlock(env).toCharArray();
    }

    private String getEnvironmentBlock(Map<String, String> env)
    {
        // Sort by name using UPPERCASE collation
        List<Map.Entry<String, String>> list = new ArrayList<>(env.entrySet());
        Collections.sort(list, new EntryComparator());

        StringBuilder sb = new StringBuilder(32 * env.size());
        for (Map.Entry<String, String> e : list)
        {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\u0000');
        }

        // Add final NUL termination
        sb.append('\u0000').append('\u0000');
        return sb.toString();
    }

    private static final class NameComparator implements Comparator<String>
    {
        public int compare(String s1, String s2)
        {
            int len1 = s1.length();
            int len2 = s2.length();
            for (int i = 0; i < Math.min(len1, len2); i++)
            {
                char c1 = s1.charAt(i);
                char c2 = s2.charAt(i);
                if (c1 != c2)
                {
                    c1 = Character.toUpperCase(c1);
                    c2 = Character.toUpperCase(c2);
                    if (c1 != c2)
                    {
                        return c1 - c2;
                    }
                }
            }

            return len1 - len2;
        }
    }

    private static final class EntryComparator implements Comparator<Map.Entry<String, String>>
    {
        static NameComparator nameComparator = new NameComparator();

        public int compare(Map.Entry<String, String> e1, Map.Entry<String, String> e2)
        {
            return nameComparator.compare(e1.getKey(), e2.getKey());
        }
    }

    static final class PipeBundle
    {
        int ioCompletionKey;
        HANDLE pipeHandle;
        ByteBuffer buffer;
        final OVERLAPPED overlapped;

        {
            overlapped = new OVERLAPPED();
        }
    }
}
