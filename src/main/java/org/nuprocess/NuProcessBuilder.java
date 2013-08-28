package org.nuprocess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 * @author Brett Wooldridge
 */
public class NuProcessBuilder
{
    private static final NuProcessFactory factory;

    private List<String> command;
    private TreeMap<String, String> environment;
    private NuProcessHandler processListener;

    static
    {
        String factoryClassName = null;
        String osname = System.getProperty("os.name").toLowerCase();
        if (osname.contains("mac"))
        {
            factoryClassName = "org.nuprocess.osx.OsxProcessFactory";
        }
        else if (osname.contains("win"))
        {
            factoryClassName = "org.nuprocess.windows.WinProcessFactory";
        }
        else if (osname.contains("linux"))
        {
            factoryClassName = "org.nuprocess.linux.LinProcessFactory";
        }
        else if (osname.contains("sunos"))
        {
            factoryClassName = "org.nuprocess.solaris.SolProcessFactory";
        }

        if (factoryClassName == null)
        {
            throw new RuntimeException("Unsupported operating system: " + osname);
        }

        try
        {
            Class<?> forName = Class.forName(factoryClassName);
            factory = (NuProcessFactory) forName.newInstance();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public NuProcessBuilder(List<String> command, NuProcessHandler listener)
    {
        if (command == null || command.isEmpty())
        {
            throw new IllegalArgumentException("List of commands may not be null or empty");
        }

        if (listener == null)
        {
            throw new IllegalArgumentException("A NuProcessListener must be specified");
        }

        this.environment = new TreeMap<String, String>(System.getenv());
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

    public void setProcessListener(NuProcessHandler listener)
    {
        if (listener == null)
        {
            throw new IllegalArgumentException("A NuProcessListener must be specified");
        }

        this.processListener = listener;
    }

    public NuProcess start()
    {
        String[] env = new String[environment.size()];
        int i = 0;
        for (Entry<String, String> entrySet : environment.entrySet())
        {
            env[i++] = entrySet.getKey() + "=" + entrySet.getValue();
        }

        NuProcess p = factory.createProcess(command, env, processListener);
        return p.start();
    }
}
