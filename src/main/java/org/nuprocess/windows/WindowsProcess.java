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
import org.nuprocess.NuProcessHandler;
import org.nuprocess.internal.UnsafeHelper;
import org.nuprocess.windows.NuKernel32.OVERLAPPED;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.WinBase.STARTUPINFO;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;

/**
 * @author Brett Wooldridge
 */
public final class WindowsProcess implements NuProcess
{
    public static final int PROCESSOR_THREADS;

    private static final boolean IS_SOFTEXIT_DETECTION;

    private static final int BUFFER_SIZE = 65536;

    private static final ProcessCompletions[] processors;
    private static int processorRoundRobin;

    private static final AtomicInteger namedPipeCounter;

    private ProcessCompletions myProcessor;
    private NuProcessHandler processListener;

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

    private int remainingWrite;
    private int writeOffset;

    private volatile boolean inClosed;
    private volatile boolean outClosed;
    private volatile boolean errClosed;

    private PROCESS_INFORMATION processInfo;

    static
    {
        namedPipeCounter = new AtomicInteger(100);

        IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("org.nuprocess.softExitDetection", "true"));

        String threads = System.getProperty("org.nuprocess.threads", "auto");
        if ("auto".equals(threads))
        {
            PROCESSOR_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        }
        else if ("cores".equals(threads))
        {
            PROCESSOR_THREADS = Runtime.getRuntime().availableProcessors();
        }
        else
        {
            PROCESSOR_THREADS = Math.max(1, Integer.parseInt(threads));
        }

        processors = new ProcessCompletions[PROCESSOR_THREADS];
        for (int i = 0; i < PROCESSOR_THREADS; i++)
        {
            processors[i] = new ProcessCompletions();
        }
    }

    public WindowsProcess(List<String> commands, String[] env, NuProcessHandler processListener)
    {
        this.commands = commands.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;

        this.userWantsWrite = new AtomicBoolean();
        this.exitCode = new AtomicInteger();
        this.exitPending = new CountDownLatch(1);
        this.outClosed = true;
        this.errClosed = true;
        this.inClosed = true;
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
            startupInfo.dwFlags = WinNT.STARTF_USESTDHANDLES;

            processInfo = new PROCESS_INFORMATION();

            DWORD dwCreationFlags = new DWORD(WinNT.CREATE_NO_WINDOW | WinNT.CREATE_UNICODE_ENVIRONMENT | WinNT.CREATE_SUSPENDED);
            if (!NuKernel32.CreateProcessW(null, getCommandLine(), null /*lpProcessAttributes*/, null /*lpThreadAttributes*/, true /*bInheritHandles*/,
                                         dwCreationFlags, env, null /*lpCurrentDirectory*/, startupInfo, processInfo))
            {
                int lastError = Native.getLastError();
                throw new RuntimeException("CreateProcessW() failed, error: " + lastError);
            }

            afterStart();

            registerProcess();

            callStart();

            NuKernel32.ResumeThread(processInfo.hThread);
        }
        catch (Throwable e)
        {
            e.printStackTrace();
            onExit(Integer.MIN_VALUE);
        }
        finally
        {
            NuKernel32.CloseHandle(hStdinWidow);
            NuKernel32.CloseHandle(hStdoutWidow);
            NuKernel32.CloseHandle(hStderrWidow);
        }

        return this;
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        // TODO: implement blocking wait
        return exitCode.get();
    }

    @Override
    public void wantWrite()
    {
        if (hStdinWidow != null && !WinBase.INVALID_HANDLE_VALUE.getPointer().equals(hStdinWidow.getPointer()))
        {
            userWantsWrite.set(true);
            myProcessor.wantWrite(this);
        }
    }

    @Override
    public void stdinClose()
    {
        if (!inClosed)
        {
            NuKernel32.CloseHandle(stdinPipe.pipeHandle);
            inClosed = true;
        }
    }

    @Override
    public void destroy()
    {
        // TODO: implement destroy
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

    void readStdout(int transferred)
    {
        if (outClosed)
        {
            return;
        }

        try
        {
            if (transferred < 0)
            {
                outClosed = true;
                processListener.onStdout(null);
                return;
            }
            else if (transferred == 0)
            {
                return;
            }

            stdoutPipe.buffer.position(0);
            stdoutPipe.buffer.limit(transferred);

            processListener.onStdout(stdoutPipe.buffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            e.printStackTrace();
        }
    }

    void readStderr(int transferred)
    {
        if (errClosed)
        {
            return;
        }

        try
        {
            if (transferred < 0)
            {
                errClosed = true;
                processListener.onStderr(null);
                return;
            }
            else if (transferred == 0)
            {
                return;
            }

            stderrPipe.buffer.position(0);
            stderrPipe.buffer.limit(transferred);

            processListener.onStderr(stderrPipe.buffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
            e.printStackTrace();
        }
    }

    boolean writeStdin(int transferred)
    {
        writeOffset += transferred;
        remainingWrite -= transferred;
        if (remainingWrite > 0)
        {
            NuKernel32.WriteFile(stdinPipe.pipeHandle, stdinPipe.bufferPointer.share(writeOffset), remainingWrite, null,stdinPipe.overlapped);

            return false;
        }
        
        if (userWantsWrite.compareAndSet(true, false))
        {
            remainingWrite = 0;
            writeOffset = 0;

            try
            {
                stdinPipe.buffer.clear();
                userWantsWrite.set(processListener.onStdinReady(stdinPipe.buffer));
                remainingWrite = stdinPipe.buffer.remaining();

                return true;
            }
            catch (Exception e)
            {
                // Don't let an exception thrown from the user's handler interrupt us
                e.printStackTrace();
                return false;
            }
        }

        return false;
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
            e.printStackTrace();
        }
        finally
        {
            if (!inClosed)
            {
                NuKernel32.CloseHandle(stdinPipe.pipeHandle);
            }

            NuKernel32.CloseHandle(stdoutPipe.pipeHandle);
            NuKernel32.CloseHandle(stderrPipe.pipeHandle);
            NuKernel32.CloseHandle(processInfo.hThread);
            NuKernel32.CloseHandle(processInfo.hProcess);

            Native.free(Pointer.nativeValue(stdoutPipe.bufferPointer));
            Native.free(Pointer.nativeValue(stderrPipe.bufferPointer));
            Native.free(Pointer.nativeValue(stdinPipe.bufferPointer));

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
            e.printStackTrace();
        }
    }

    private void createPipes()
    {
        SECURITY_ATTRIBUTES sattr = new SECURITY_ATTRIBUTES();
        sattr.dwLength = new DWORD(sattr.size());
        sattr.bInheritHandle = true;
        sattr.lpSecurityDescriptor = null;

        // ################ STDOUT PIPE ################
        long ioCompletionKey = namedPipeCounter.getAndIncrement();
        WString pipeName = new WString("\\\\.\\pipe\\NuProcess" + ioCompletionKey);
        hStdoutWidow = NuKernel32.CreateNamedPipeW(pipeName, NuKernel32.PIPE_ACCESS_INBOUND,
                                                   0 /*dwPipeMode*/, 1 /*nMaxInstances*/,
                                                   BUFFER_SIZE, BUFFER_SIZE,
                                                   0 /*nDefaultTimeOut*/, sattr);
        checkHandleValidity(hStdoutWidow);

        HANDLE stdoutHandle = NuKernel32.CreateFile(pipeName, WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ, null,
                                                    WinNT.OPEN_EXISTING,
                                                    WinNT.FILE_ATTRIBUTE_NORMAL | WinNT.FILE_FLAG_OVERLAPPED,
                                                    null /*hTemplateFile*/);
        checkHandleValidity(stdoutHandle);
        stdoutPipe = new PipeBundle(stdoutHandle, ioCompletionKey);
        checkPipeConnected(NuKernel32.ConnectNamedPipe(hStdoutWidow, null));

        // ################ STDERR PIPE ################
        ioCompletionKey = namedPipeCounter.getAndIncrement();
        pipeName = new WString("\\\\.\\pipe\\NuProcess" + ioCompletionKey);
        hStderrWidow = NuKernel32.CreateNamedPipeW(pipeName, NuKernel32.PIPE_ACCESS_INBOUND,
                                                   0 /*dwPipeMode*/, 1 /*nMaxInstances*/,
                                                   BUFFER_SIZE, BUFFER_SIZE,
                                                   0 /*nDefaultTimeOut*/, sattr);
        checkHandleValidity(hStderrWidow);

        HANDLE stderrHandle = NuKernel32.CreateFile(pipeName, WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ, null,
                                                    WinNT.OPEN_EXISTING,
                                                    WinNT.FILE_ATTRIBUTE_NORMAL | WinNT.FILE_FLAG_OVERLAPPED,
                                                    null /*hTemplateFile*/);
        checkHandleValidity(stderrHandle);
        stderrPipe = new PipeBundle(stderrHandle, ioCompletionKey);
        checkPipeConnected(NuKernel32.ConnectNamedPipe(hStderrWidow, null));

        // ################ STDIN PIPE ################
        ioCompletionKey = namedPipeCounter.getAndIncrement();
        pipeName = new WString("\\\\.\\pipe\\NuProcess" + ioCompletionKey);
        hStdinWidow = NuKernel32.CreateNamedPipeW(pipeName, NuKernel32.PIPE_ACCESS_OUTBOUND,
                                                  0 /*dwPipeMode*/, 1 /*nMaxInstances*/, 
                                                  BUFFER_SIZE, BUFFER_SIZE,
                                                  0 /*nDefaultTimeOut*/, sattr);
        checkHandleValidity(hStdinWidow);

        HANDLE stdinHandle = NuKernel32.CreateFile(pipeName, WinNT.GENERIC_WRITE, WinNT.FILE_SHARE_WRITE, null,
                                                   WinNT.OPEN_EXISTING,
                                                   WinNT.FILE_ATTRIBUTE_NORMAL | WinNT.FILE_FLAG_OVERLAPPED,
                                                   null /*hTemplateFile*/);
        checkHandleValidity(stdinHandle);
        stdinPipe = new PipeBundle(stdinHandle, ioCompletionKey);
        checkPipeConnected(NuKernel32.ConnectNamedPipe(hStdinWidow, null));
    }

    private void afterStart()
    {
        commands = null;
        environment = null;

        outClosed = false;
        errClosed = false;
        inClosed = false;

        long peer = Native.malloc(BUFFER_CAPACITY);
        stdoutPipe.buffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
        stdoutPipe.bufferPointer = new Pointer(peer);

        peer = Native.malloc(BUFFER_CAPACITY);
        stderrPipe.buffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
        stderrPipe.bufferPointer = new Pointer(peer);

        peer = Native.malloc(BUFFER_CAPACITY);
        stdinPipe.buffer = UnsafeHelper.wrapNativeMemory(peer, BUFFER_CAPACITY);
        stdinPipe.bufferPointer = new Pointer(peer);
    }

    private void registerProcess()
    {
        int mySlot = 0;
        synchronized (processors)
        {
            mySlot = processorRoundRobin;
            processorRoundRobin = (processorRoundRobin + 1) % processors.length;
        }

        myProcessor = processors[mySlot];
        myProcessor.registerProcess(this);

        if (myProcessor.checkAndSetRunning())
        {
            CyclicBarrier spawnBarrier = myProcessor.getSpawnBarrier();

            Thread t = new Thread(myProcessor, "ProcessIoCompletion" + mySlot);
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
        List<Map.Entry<String, String>> list = new ArrayList<Map.Entry<String, String>>(env.entrySet());
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

    private void checkHandleValidity(HANDLE handle)
    {
        if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(handle))
        {
            throw new RuntimeException("Unable to create pipe, error " + Native.getLastError());
        }
    }

    private void checkPipeConnected(int status)
    {
        int lastError;
        if (status == 0 && ((lastError = Native.getLastError()) != WinNT.ERROR_PIPE_CONNECTED))
        {
            throw new RuntimeException("Unable to connect pipe, error: " + lastError);
        }
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
        final OVERLAPPED overlapped;
        final long ioCompletionKey;
        final HANDLE pipeHandle;
        ByteBuffer buffer;
        Pointer bufferPointer;
        boolean registered;

        PipeBundle(HANDLE pipeHandle, long ioCompletionKey)
        {
            this.pipeHandle = pipeHandle;
            this.ioCompletionKey = ioCompletionKey;
            this.overlapped = new OVERLAPPED();
        }
    }
}
