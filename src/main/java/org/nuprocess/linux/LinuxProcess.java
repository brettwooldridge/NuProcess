package org.nuprocess.linux;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;
import org.nuprocess.linux.LibC;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess implements NuProcess
{
    private static final LibC LIBC;

    int pid;
    int stdin;
    int stdout;
    int stderr;

    private String[] args;
    private String[] environment;
    private NuProcessListener processListener;

    private ByteBuffer outBuffer;

    static
    {
        LIBC = LibC.INSTANCE;
    }

    public LinuxProcess(List<String> command, String[] env, NuProcessListener processListener)
    {
        this.args = command.toArray(new String[0]);
        this.environment = env;
        this.processListener = processListener;
        this.outBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
    }

    @Override
    public int write(byte[] buf) throws IOException
    {
        return 0;
    }

    @Override
    public int write(byte[] buf, int off, int len) throws IOException
    {
        return 0;
    }

    @Override
    public int write(ByteBuffer buf) throws IOException
    {
        return 0;
    }

    @Override
    public void stdinClose()
    {

    }
}
