/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.nuprocess;

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
            || System.getProperty("os.name").toLowerCase().contains("solaris")
            || System.getProperty("os.name").toLowerCase().contains("freebsd"))
        {
            super.run(notifier);
        }
    }
}
