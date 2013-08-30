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

package org.nuprocess.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Brett Wooldridge
 */
public abstract class BaseEventProcessor<T extends BasePosixProcess> implements IEventProcessor<T>
{
    protected static final int EVENT_BATCH_SIZE;
    protected static final int DEADPOOL_POLL_INTERVAL;
    protected static final int LINGER_ITERATIONS;

    protected Map<Integer, T> pidToProcessMap;
    protected Map<Integer, T> fildesToProcessMap;

    private CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    static
    {
        EVENT_BATCH_SIZE = Integer.getInteger("org.nuprocess.eventBatchSize", 1); 

        int lingerTimeMs = Math.max(1000, Integer.getInteger("org.nuprocess.lingerTimeMs", 2500));

        DEADPOOL_POLL_INTERVAL = Math.min(lingerTimeMs, Math.max(100, Integer.getInteger("org.nuprocess.deadPoolPollMs", 250)));
        
        LINGER_ITERATIONS = lingerTimeMs / DEADPOOL_POLL_INTERVAL;
    }

    public BaseEventProcessor()
    {
        pidToProcessMap = new ConcurrentHashMap<Integer, T>();
        fildesToProcessMap = new ConcurrentHashMap<Integer, T>();
        isRunning = new AtomicBoolean();
    }

    /**
     * The primary run loop of the kqueue event processor.
     */
    @Override
    public void run()
    {
        try
        {
            startBarrier.await();

            int idleCount = 0;
            while (!isRunning.compareAndSet(idleCount > LINGER_ITERATIONS && pidToProcessMap.isEmpty(), false))
            {
                idleCount = process() ? 0 : (idleCount + 1);
            }
        }
        catch (Exception e)
        {
            // TODO: how to handle this error?
            isRunning.set(false);
        }
    }


    /**
     * Get the CyclicBarrier that this thread should join, along with the OsxProcess
     * thread that is starting this processor.  Used to cause the OsxProcess to wait
     * until the processor is up and running before returning from start() to the
     * user.
     *
     * @param processorRunning a CyclicBarrier to join
     */
    @Override
    public CyclicBarrier getSpawnBarrier()
    {
        startBarrier = new CyclicBarrier(2);
        return startBarrier;
    }

    /**
     * @return
     */
    @Override
    public boolean checkAndSetRunning()
    {
        return isRunning.compareAndSet(false, true);
    }
}
