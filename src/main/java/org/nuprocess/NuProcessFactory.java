package org.nuprocess;

import java.util.List;

/**
 * @author Brett Wooldridge
 */
public interface NuProcessFactory
{
    NuProcess createProcess(List<String> commands, String[] env, NuProcessHandler processListener);
}
