package org.nuprocess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.nuprocess.osx.OsxProcess;

public class NuProcessBuilder
{
    private List<String> command;
    private HashMap<String, String> environment;
    private NuProcessListener processListener;
    private boolean redirectErrorStream;
    // private File directory;

    // Instance initializer
    {
        // directory = new File(".");
        environment = new HashMap<String, String>(System.getenv());
    }

    public NuProcessBuilder(List<String> command, NuProcessListener listener)
    {
        this.command(command);
        this.processListener = listener;
    }

    public List<String> command()
    {
        return command;
    }

    public NuProcessBuilder command(List<String> command)
    {
        this.command = new ArrayList<String>(command);
        return this;
    }

    public NuProcessBuilder command(String...command)
    {
        this.command = Arrays.asList(command);
        return this;
    }

    public NuProcessBuilder processListener(NuProcessListener processListener)
    {
        this.processListener = processListener;
        return this;
    }

    public boolean redirectErrorStream()
    {
        return redirectErrorStream;
    }

    public NuProcessBuilder redirectErrorStream(boolean redirectErrorStream)
    {
        this.redirectErrorStream = redirectErrorStream;
        return this;
    }

    /*
    public File directory()
    {
        return directory;
    }

    public NuProcessBuilder directory(File directory)
    {
        this.directory = directory;
        return this;
    }
    */

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

        return p.start();
    }
}
