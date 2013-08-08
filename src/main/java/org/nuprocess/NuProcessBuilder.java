package org.nuprocess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.nuprocess.osx.OsxProcess;

/**
 * @author Brett Wooldridge
 */
public class NuProcessBuilder
{
    private List<String> command;
    private HashMap<String, String> environment;
    private NuProcessListener processListener;

    // Instance initializer
    {
        environment = new HashMap<String, String>(System.getenv());
    }

    public NuProcessBuilder(List<String> command, NuProcessListener listener)
    {
        if (command == null || command.isEmpty())
        {
            throw new IllegalArgumentException("List of commands may not be null or empty");
        }

        if (listener == null)
        {
            throw new IllegalArgumentException("A NuProcessListener must be specified");
        }

        this.command = new ArrayList<String>(command);
        this.processListener = listener;
    }

    public List<String> command()
    {
        return command;
    }

    public Map<String, String> environment()
    {
        return environment;
    }

    public NuProcess start()
    {
        String[] env = new String[environment.size()];
        int i = 0;
        for (Entry<String, String> entrySet : environment.entrySet())
        {
            env[i++] = entrySet.getKey() + "=" + entrySet.getValue();
        }

        OsxProcess p = new OsxProcess(command, env, processListener);
        // LinuxProcess p = new LinuxProcess(command, env, processListener);
        return p.start();
    }
}
