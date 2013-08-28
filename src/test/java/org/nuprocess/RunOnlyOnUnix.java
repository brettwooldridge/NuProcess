package org.nuprocess;

import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

public class RunOnlyOnUnix extends BlockJUnit4ClassRunner
{
    public RunOnlyOnUnix(Class<?> klass) throws InitializationError
    {
        super(klass);
    }

    @Override
    public void run(RunNotifier notifier)
    {
        if (System.getProperty("os.name").toLowerCase().contains("linux")
            || System.getProperty("os.name").toLowerCase().contains("mac")
            || System.getProperty("os.name").toLowerCase().contains("solaris"))
        {
            super.run(notifier);
        }
    }
}
