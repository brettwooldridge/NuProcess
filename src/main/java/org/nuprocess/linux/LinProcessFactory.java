package org.nuprocess.linux;

import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessFactory;
import org.nuprocess.NuProcessHandler;

public class LinProcessFactory implements NuProcessFactory
{
    @Override
    public NuProcess createProcess(List<String> commands, String[] env, NuProcessHandler processListener)
    {
        return new LinuxProcess(commands, env, processListener);
    }
}
