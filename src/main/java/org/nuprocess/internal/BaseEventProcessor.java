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
    public static final int EVENT_BATCH_SIZE;

    protected Map<Integer, T> pidToProcessMap;
    protected Map<Integer, T> fildesToProcessMap;

    private CyclicBarrier startBarrier;
    private AtomicBoolean isRunning;

    static
    {
        EVENT_BATCH_SIZE = Integer.getInteger("org.nuprocess.eventBatchSize", 8); 
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

            do
            {
                process();
            }
            while (!isRunning.compareAndSet(pidToProcessMap.isEmpty(), false));
            isRunning.set(false);
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
