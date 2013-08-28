package org.nuprocess.osx;

import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessFactory;
import org.nuprocess.NuProcessHandler;

public class OsxProcessFactory implements NuProcessFactory
{
    @Override
    public NuProcess createProcess(List<String> commands, String[] env, NuProcessHandler processListener)
    {
        return new OsxProcess(commands, env, processListener);
    }
}
