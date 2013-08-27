package org.nuprocess.internal;

import java.util.concurrent.CyclicBarrier;

public interface IEventProcessor<T extends BasePosixProcess> extends Runnable
{
    boolean checkAndSetRunning();

    CyclicBarrier getSpawnBarrier();

    void registerProcess(T process);

    void queueWrite(int stdin);

    void closeStdin(int stdin);

    boolean process();
}
