package org.nuprocess.linux;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BaseProcess;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.objenesis.instantiator.ObjectInstantiator;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BaseProcess
{
    private static final LibC LIBC;

    private static final ObjectInstantiator processInstantiator;
    private static final Class<?> processClass;
    private static boolean JDK7;
    private static Method forkAndExec;
    private static Method destroyProcess;

    private CountDownLatch exitPending;

    private Object unixProcessInstance;

    static
    {
        LIBC = LibC.INSTANCE;

        try
        {
            processClass = Class.forName("java.lang.UNIXProcess");
        }
        catch (ClassNotFoundException e)
        {
            throw new RuntimeException(e);
        }

        Objenesis objenesis = new ObjenesisStd();
        processInstantiator = objenesis.getInstantiatorOf(processClass);
        if (processInstantiator == null)
        {
            throw new RuntimeException("Unable to create instantiator for UNIXProcess");
        }

        reflectUnixProcess();

        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessEpoll();
        }
    }

    public LinuxProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        super(commands, env, processListener);
        this.exitPending = new CountDownLatch(1);
    }

    public NuProcess start()
    {
        int[] std_fds = {-1, -1, -1};

        // Convert arguments to a contiguous block; it's easier to do
        // memory management in Java than in C.
        byte[][] args = new byte[commands.length - 1][];
        int size = args.length; // For added NUL bytes
        for (int i = 0; i < args.length; i++)
        {
            args[i] = commands[i + 1].getBytes();
            size += args[i].length;
        }
        byte[] argBlock = new byte[size];
        int i = 0;
        for (byte[] arg : args)
        {
            System.arraycopy(arg, 0, argBlock, i, arg.length);
            i += arg.length + 1; // No need to write NUL byte explicitly, the +1 handles it
        }

        byte[] envBlock = toEnvironmentBlock();

        File workDir = new File(".");

        unixProcessInstance = processInstantiator.newInstance();
        Object[] params = { toCString(commands[0]), argBlock, args.length, envBlock, environment.length, toCString(workDir.getAbsolutePath()), std_fds, false };
        try
        {
            pid = (int) forkAndExec.invoke(unixProcessInstance, params);
            if (pid >= 0)
            {
            }
            stdin = std_fds[0];
            stdout = std_fds[1];
            stderr = std_fds[2];
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        afterStart();

        registerProcess();

        kickstartProcessors();

        callStart();

        return this;
    }

    @Override
    public int waitFor() throws InterruptedException
    {
        if (exitPending.getCount() > 0)
        {
            // TODO: call native wait
        }
        return exitCode.get();
    }

    @Override
    public void stdinClose()
    {
        if (stdin != 0)
        {
            LIBC.close(stdin);
            // stdin = 0;
        }
    }

    @Override
    public void destroy()
    {
        try
        {
            destroyProcess.invoke(unixProcessInstance, new Object[] { pid });
        }
        catch (Exception e)
        {
            // eat it
            return;
        }
        finally
        {
            exitPending.countDown();
        }
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    void readStdout(boolean closed)
    {
        try
        {
            if (closed)
            {
                processListener.onStdout(null);
                return;
            }

            outBuffer.clear();
            int read = LIBC.read(stdout, outBuffer, BUFFER_CAPACITY);
            if (read == -1)
            {
                // EOF?
            }
            outBuffer.limit(read);
            processListener.onStdout(outBuffer);
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
            if (closed)
            {
                processListener.onStderr(null);
                return;
            }

            outBuffer.clear();
            int read = LIBC.read(stderr, outBuffer, BUFFER_CAPACITY);
            if (read == -1)
            {
                // EOF?
            }
            outBuffer.limit(read);
            processListener.onStderr(outBuffer);
        }
        catch (Exception e)
        {
            // Don't let an exception thrown from the user's handler interrupt us
        }
    }

    boolean writeStdin()
    {
        while (true)
        {
            if (inBuffer.limit() < inBuffer.capacity())
            {
                ByteBuffer slice = inBuffer.slice();
                int wrote = LIBC.write(stdin, slice, slice.capacity());
                if (wrote == -1)
                {
                    // EOF?
                    return false;
                }

                inBuffer.position(inBuffer.position() + wrote);
                if (userWantsWrite.compareAndSet(false, false))
                {
                    return (wrote == slice.capacity() ? false : true);
                }
            }

            try
            {
                inBuffer.clear();
                boolean wantMore = processListener.onStdinReady(inBuffer);
                userWantsWrite.set(wantMore);
                return wantMore;
            }
            catch (Exception e)
            {
                // Don't let an exception thrown from the user's handler interrupt us
                return false;
            }
        }
    }

    @Override
    protected void close(int fd)
    {
        if (fd != 0)
        {
            LIBC.close(fd);
        }
    }

    // ************************************************************************
    //                             Private methods
    // ************************************************************************

    // Convert to Unix style environ as a monolithic byte array
    // inspired by the Windows Environment Block, except we work
    // exclusively with bytes instead of chars, and we need only
    // one trailing NUL on Unix.
    private byte[] toEnvironmentBlock()
    {
        int count = environment.length; // for added NUL
        for (String env : environment)
        {
            count += env.getBytes().length;
        }

        byte[] block = new byte[count];

        int i = 0;
        for (String env : environment)
        {
            byte[] val = env.getBytes();
            System.arraycopy(val, 0, block, i, val.length);
            i += val.length + 1; // No need to write NUL byte explicitly, the +1 handles it
        }

        return block;
    }

    private byte[] toCString(String s)
    {
        if (s == null)
            return null;
        byte[] bytes = s.getBytes();
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        result[result.length - 1] = (byte) 0;
        return result;
    }

    private static void reflectUnixProcess()
    {
        java.security.AccessController.doPrivileged(new java.security.PrivilegedAction<Method>()
        {
            public Method run()
            {
                try
                {
                    for (Method method : processClass.getDeclaredMethods())
                    {
                        if ("forkAndExec".equals(method.getName()))
                        {
                            JDK7 = method.getParameterTypes().length == 8;
                            method.setAccessible(true);
                            forkAndExec = method;
                        }
                        else if ("destroyProcess".equals(method.getName()))
                        {
                            method.setAccessible(true);
                            destroyProcess = method;
                        }
                    }
                    return null;
                }
                catch (Exception e)
                {
                    return null;
                }
            }
        });
    }
}
