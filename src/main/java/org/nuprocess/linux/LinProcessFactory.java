package org.nuprocess.linux;

import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessFactory;
import org.nuprocess.NuProcessListener;

public class LinProcessFactory implements NuProcessFactory
{
    @Override
    public NuProcess createProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        return new LinuxProcess(commands, env, processListener);
    }
}
