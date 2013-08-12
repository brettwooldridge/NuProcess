package org.nuprocess;

import org.apache.commons.lang3.SystemUtils;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

public class RunOnlyOnWindows extends BlockJUnit4ClassRunner
{
    public RunOnlyOnWindows(Class<?> klass) throws InitializationError
    {
        super(klass);
    }

    @Override
    public void run(RunNotifier notifier)
    {
        if (SystemUtils.IS_OS_WINDOWS)
        {
            super.run(notifier);
        }
    }
}
