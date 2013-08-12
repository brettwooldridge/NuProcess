package org.nuprocess.windows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;

import com.sun.jna.Memory;
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

            char[] block = getEnvironment();
            Memory env = new Memory(block.length * 3);
            env.write(0, block, 0, block.length);

            STARTUPINFO startupInfo = new STARTUPINFO();
            startupInfo.clear();
            startupInfo.cb = new DWORD(startupInfo.size());
            startupInfo.hStdInput = hStdinRead;
            startupInfo.hStdError = hStderrWrite;
            startupInfo.hStdOutput = hStdoutWrite;
            startupInfo.dwFlags = Kernel32.STARTF_USESTDHANDLES;

            PROCESS_INFORMATION processInfo = new PROCESS_INFORMATION();

            DWORD dwCreationFlags = new DWORD(Kernel32.CREATE_NO_WINDOW); // | Kernel32.CREATE_UNICODE_ENVIRONMENT);
            if (!KERNEL32.CreateProcessW(null, getCommandLine(), null /*lpProcessAttributes*/, null /*lpThreadAttributes*/, true /*bInheritHandles*/,
                                         dwCreationFlags, null /*env*/, null /*lpCurrentDirectory*/, startupInfo, processInfo))
            {
                throw new RuntimeException("CreateProcessW() failed");
            }

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
        sb.append('\u0000');
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
