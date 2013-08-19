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

    private AtomicBoolean userWantsWrite;

    private HANDLE hProcess;
    private HANDLE hStdin;
    private HANDLE hStdinWidow;
    private HANDLE hStdout;
    private HANDLE hStdoutWidow;
    private HANDLE hStderr;
    private HANDLE hStderrWidow;

    private int stdinKey;
    private int stderrKey;
    private int stdoutKey;

    private ByteBuffer outBuffer;
    private ByteBuffer inBuffer;

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

        this.hStdinWidow = new HANDLE();
        this.hStdin = new HANDLE();
        this.hStdout = new HANDLE();
        this.hStdoutWidow = new HANDLE();
        this.hStderr = new HANDLE();
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

            PROCESS_INFORMATION processInfo = new PROCESS_INFORMATION();

            DWORD dwCreationFlags = new DWORD(Kernel32.CREATE_NO_WINDOW | Kernel32.CREATE_UNICODE_ENVIRONMENT | Kernel32.CREATE_SUSPENDED);
            if (!KERNEL32.CreateProcessW(null, getCommandLine(), null /*lpProcessAttributes*/, null /*lpThreadAttributes*/, true /*bInheritHandles*/,
                                         dwCreationFlags, env, null /*lpCurrentDirectory*/, startupInfo, processInfo))
            {
                throw new RuntimeException("CreateProcessW() failed");
            }

            hProcess = processInfo.hThread;

            afterStart();

            registerProcess();

            kickstartProcessors();

            callStart();

            KERNEL32.ResumeThread(hProcess);
        }
        catch (RuntimeException e)
        {

        }

        return this;
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        return 0;
    }

    @Override
    public void wantWrite()
    {
        if (hStdinWidow != null && !WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdoutWidow.getPointer()))
        {
            userWantsWrite.set(true);
            myProcessor.requeueRead(hStdinWidow);
        }
    }

    @Override
    public void stdinClose()
    {

    }

    @Override
    public void destroy()
    {
    }

    HANDLE getPid()
    {
        return hProcess;
    }

    HANDLE getStdin()
    {
        return hStdin;
    }

    int getStdinKey()
    {
        return stdinKey;
    }

    HANDLE getStdout()
    {
        return hStdout;
    }

    int getStdoutKey()
    {
        return stdoutKey;
    }

    HANDLE getStderr()
    {
        return hStderr;
    }

    int getStderrKey()
    {
        return stderrKey;
    }

    ByteBuffer getOutBuffer()
    {
        return outBuffer;
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
        stdoutKey = namedPipeCounter.getAndIncrement();
        String pipeName = "\\\\.\\pipe\\NuProcess" + stdoutKey;
        hStdoutWidow = KERNEL32.CreateNamedPipe(pipeName, dwOpenMode, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                       0 /*nDefaultTimeOut*/, sattr);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdoutWidow.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        hStdout = KERNEL32.CreateFile(pipeName, Kernel32.GENERIC_READ, Kernel32.FILE_SHARE_READ /*dwShareMode*/, null,
                                          Kernel32.OPEN_EXISTING /*dwCreationDisposition*/, Kernel32.FILE_ATTRIBUTE_NORMAL | Kernel32.FILE_FLAG_OVERLAPPED /*dwFlagsAndAttributes*/,
                                          null /*hTemplateFile*/);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdout.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        OVERLAPPED overlapped = new OVERLAPPED();
        overlapped.clear();
        boolean status = KERNEL32.ConnectNamedPipe(hStdoutWidow, overlapped);
        lastError = KERNEL32.GetLastError();
        if (!status && (lastError != Kernel32.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }

        /* stderr pipe */
        stderrKey = namedPipeCounter.getAndIncrement();
        pipeName = "\\\\.\\pipe\\NuProcess" + stderrKey;
        hStderrWidow = KERNEL32.CreateNamedPipe(pipeName, dwOpenMode, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                       0 /*nDefaultTimeOut*/, sattr);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStderrWidow.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        hStderr = KERNEL32.CreateFile(pipeName, Kernel32.GENERIC_READ, Kernel32.FILE_SHARE_READ /*dwShareMode*/, null,
                                          Kernel32.OPEN_EXISTING /*dwCreationDisposition*/, Kernel32.FILE_ATTRIBUTE_NORMAL | Kernel32.FILE_FLAG_OVERLAPPED /*dwFlagsAndAttributes*/,
                                          null /*hTemplateFile*/);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStderr.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        overlapped = new OVERLAPPED();
        overlapped.clear();
        status = KERNEL32.ConnectNamedPipe(hStderrWidow, overlapped);
        lastError = KERNEL32.GetLastError();
        if (!status && (lastError != Kernel32.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }

        /* stdin pipe */
        stdinKey = namedPipeCounter.getAndIncrement();
        dwOpenMode = NuKernel32.PIPE_ACCESS_OUTBOUND | NuKernel32.FILE_FLAG_OVERLAPPED;
        pipeName = "\\\\.\\pipe\\NuProcess" + stdinKey;
        hStdinWidow = KERNEL32.CreateNamedPipe(pipeName, dwOpenMode, 0 /*dwPipeMode*/, 1 /*nMaxInstances*/, BUFFER_SIZE, BUFFER_SIZE,
                                                       0 /*nDefaultTimeOut*/, sattr);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdinWidow.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        hStdin = KERNEL32.CreateFile(pipeName, Kernel32.GENERIC_WRITE, Kernel32.FILE_SHARE_WRITE /*dwShareMode*/, null,
                                          Kernel32.OPEN_EXISTING /*dwCreationDisposition*/, Kernel32.FILE_ATTRIBUTE_NORMAL | Kernel32.FILE_FLAG_OVERLAPPED /*dwFlagsAndAttributes*/,
                                          null /*hTemplateFile*/);
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdin.getPointer()))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        overlapped = new OVERLAPPED();
        overlapped.clear();
        status = KERNEL32.ConnectNamedPipe(hStdinWidow, overlapped);
        lastError = KERNEL32.GetLastError();
        if (!status && (lastError != Kernel32.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }
    }

    private void afterStart()
    {
        commands = null;
        environment = null;

        outBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        inBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
        inBuffer.flip();
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
}
