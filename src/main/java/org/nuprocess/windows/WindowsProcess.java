package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase.PROCESS_INFORMATION;
import com.sun.jna.platform.win32.WinBase.SECURITY_ATTRIBUTES;
import com.sun.jna.platform.win32.WinBase.STARTUPINFO;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;

/**
 * @author Brett Wooldridge
 */
public class WindowsProcess implements NuProcess
{
    private static final Kernel32 KERNEL32 = Kernel32.INSTANCE;
    private static final NuKernel32 NU_KERNEL32 = NuKernel32.INSTANCE;

    private NuProcessListener processListener;

    private String[] environment;
    private String[] commands;
    private AtomicInteger exitCode;
    private CountDownLatch exitPending;

    private AtomicBoolean userWantsWrite;

    private HANDLE hStdinRead;
    private HANDLE hStdinWrite;
    private HANDLE hStdoutRead;
    private HANDLE hStdoutWrite;
    private HANDLE hStderrRead;
    private HANDLE hStderrWrite;

    private ByteBuffer outBuffer;
    private ByteBuffer inBuffer;

    public WindowsProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        this.commands = commands.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;
        this.userWantsWrite = new AtomicBoolean();
        this.exitCode = new AtomicInteger();
        this.exitPending = new CountDownLatch(1);

        this.hStdinRead = new HANDLE();
        this.hStdinWrite = new HANDLE();
        this.hStdoutRead = new HANDLE();
        this.hStdoutWrite = new HANDLE();
        this.hStderrRead = new HANDLE();
        this.hStderrWrite = new HANDLE();
    }

    @Override
    public NuProcess start()
    {
        try
        {
            createPipes();
            createEnvironment();

            STARTUPINFO startupInfo = new STARTUPINFO();
            startupInfo.cb = new DWORD(startupInfo.size());
            startupInfo.hStdInput = hStdinRead;
            startupInfo.hStdError = hStderrWrite;
            startupInfo.hStdOutput = hStdoutWrite;
            startupInfo.dwFlags = Kernel32.STARTF_USESTDHANDLES;

            PROCESS_INFORMATION processInfo = new PROCESS_INFORMATION();

            DWORD dwCreationFlags = new DWORD(Kernel32.CREATE_NO_WINDOW | Kernel32.CREATE_UNICODE_ENVIRONMENT);
            if (!KERNEL32.CreateProcessW(null,
                                         getCommandLine(),
                                         null /*lpProcessAttributes*/,
                                         null /*lpThreadAttributes*/,
                                         false /*bInheritHandles*/,
                                         dwCreationFlags,
                                         null /*lpEnvironment*/,
                                         null /*lpCurrentDirectory*/,
                                         startupInfo,
                                         processInfo))
            {
                throw new RuntimeException("CreateProcessW() failed");
            }

        }
        catch (RuntimeException e)
        {
            
        }

        return this;
    }

    private void createEnvironment()
    {
        Pointer blockW = NU_KERNEL32.GetEnvironmentStringsW();

        int i = 0;
        while (true)
        {
            if (blockW.getChar(i) == 0 && blockW.getChar(i+2) == 0)
            {
                break;
            }
            i += 2;
        }

        String s = new String(blockW.getCharArray(0, i / 2));
        NU_KERNEL32.FreeEnvironmentStringsW(blockW);
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        return 0;
    }

    @Override
    public void wantWrite()
    {

    }

    @Override
    public void stdinClose()
    {

    }

    @Override
    public void destroy()
    {
    }

    private void createPipes()
    {
        SECURITY_ATTRIBUTES sattr = new SECURITY_ATTRIBUTES();
        sattr.dwLength = new DWORD(sattr.size());
        sattr.bInheritHandle = true;
        sattr.lpSecurityDescriptor = null;

        HANDLEByReference ref1 = new HANDLEByReference();
        HANDLEByReference ref2 = new HANDLEByReference();
        if (!KERNEL32.CreatePipe(ref1, ref2, sattr, 0))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        hStdoutRead = ref1.getValue();
        hStdoutWrite = ref2.getValue();
        if (!KERNEL32.SetHandleInformation(hStdoutRead, Kernel32.HANDLE_FLAG_INHERIT, 0))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        ref1 = new HANDLEByReference();
        ref2 = new HANDLEByReference();
        if (!KERNEL32.CreatePipe(ref1, ref2, sattr, 0))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        hStderrRead = ref1.getValue();
        hStderrWrite = ref2.getValue();
        if (!KERNEL32.SetHandleInformation(hStderrRead, Kernel32.HANDLE_FLAG_INHERIT, 0))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        ref1 = new HANDLEByReference();
        ref2 = new HANDLEByReference();
        if (!KERNEL32.CreatePipe(ref1, ref2, sattr, 0))
        {
            throw new RuntimeException("Unable to create pipe");
        }

        hStdinRead = ref1.getValue();
        hStdinWrite = ref2.getValue();
        if (!KERNEL32.SetHandleInformation(hStdinWrite, Kernel32.HANDLE_FLAG_INHERIT, 0))
        {
            throw new RuntimeException("Unable to create pipe");
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

        return sb.toString().toCharArray();
    }
}
