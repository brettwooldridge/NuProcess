/**
 * 
 */
package org.nuprocess.windows;

import java.util.List;

import org.nuprocess.NuProcess;
import org.nuprocess.NuProcessFactory;
import org.nuprocess.NuProcessHandler;

/**
 * @author Brett Wooldridge
 */
public class WinProcessFactory implements NuProcessFactory
{
    @Override
    public NuProcess createProcess(List<String> commands, String[] env, NuProcessHandler processListener)
    {
        return new WindowsProcess(commands, env, processListener);
    }
}
