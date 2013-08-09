package org.nuprocess.osx;

import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessFactory;
import org.nuprocess.NuProcessListener;

public class OsxProcessFactory implements NuProcessFactory
{
    @Override
    public NuProcess createProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        return new OsxProcess(commands, env, processListener);
    }
}
