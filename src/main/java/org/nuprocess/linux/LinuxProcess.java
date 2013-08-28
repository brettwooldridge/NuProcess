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

package org.nuprocess.linux;

import java.util.List;

import org.nuprocess.NuProcessHandler;
import org.nuprocess.internal.BasePosixProcess;

/**
 * @author Brett Wooldridge
 */
public class LinuxProcess extends BasePosixProcess
{
    private static final boolean IS_SOFTEXIT_DETECTION;

    static
    {
        LibEpoll.sigignore(LibEpoll.SIGPIPE);

        // TODO: install signal handler for SIGCHLD, and call onExit() when received, call the default (JVM) hook if the PID is not ours

        IS_SOFTEXIT_DETECTION = Boolean.valueOf(System.getProperty("org.nuprocess.linux.softExitDetection", "true"));

        for (int i = 0; i < processors.length; i++)
        {
            processors[i] = new ProcessEpoll();
        }
    }

    public LinuxProcess(List<String> commands, String[] env, NuProcessHandler processListener)
    {
        super(commands, env, processListener);
    }

    // ************************************************************************
    //                             Package methods
    // ************************************************************************

    boolean isSoftExit()
    {
        return (IS_SOFTEXIT_DETECTION && outClosed && errClosed);
    }
}
