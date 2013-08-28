package org.nuprocess.linux;

import java.util.List;

import org.nuprocess.NuProcessHandler;
import org.nuprocess.internal.BasePosixProcess;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
    private static final boolean IS_SOFTEXIT_DETECTION;

    static
    {
        LibEpoll.sigignore(LibEpoll.SIGPIPE);

        // TODO: install signal handler for SIGCHLD, and call onExit() when received, call the default (JVM) hook if the PID is not ours

        IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("org.nuprocess.linux.softExitDetection", "true"));

        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessEpoll();
        }
    }

    public LinuxProcess(List<String> commands, String[] env, NuProcessHandler processListener)
    {
        super(commands, env, processListener);
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    boolean isSoftExit()
    {
        return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
    }
}
