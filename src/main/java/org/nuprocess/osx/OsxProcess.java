package org.nuprocess.osx;

import java.util.List;

import org.nuprocess.NuProcessListener;
import org.nuprocess.internal.BasePosixProcess;

/**
 * @author Brett Wooldridge
 */
public class OsxProcess extends BasePosixProcess
{
    static
    {
        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessKqueue();
        }
    }

    public OsxProcess(List<String> commands, String[] env, NuProcessListener processListener)
    {
        super(commands, env, processListener);
    }
}
