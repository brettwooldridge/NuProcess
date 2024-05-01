package com.zaxxer.nuprocess.internal;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BasePosixProcessTest {

    @Test
    public void notConsumingOutputShouldNotCrashTheEventLoop() throws Exception {
        if (Constants.OS != Constants.OperatingSystem.LINUX && Constants.OS != Constants.OperatingSystem.MAC) {
            return;
        }

        for (int i = 0; i < 10; i++) {
            final CountDownLatch latchStart = new CountDownLatch(1);
            final CountDownLatch latchExit = new CountDownLatch(1);
            final AtomicInteger exitCode = new AtomicInteger(-1);
            final AtomicLong onStartNanoTime = new AtomicLong();
            final AtomicLong onExitNanoTime = new AtomicLong();
            final AtomicInteger methodsCallCount = new AtomicInteger();
            final AtomicInteger problems = new AtomicInteger();

            NuProcess process = new NuProcessBuilder(new NuAbstractProcessHandler() {
                @Override
                public void onStart(NuProcess nuProcess) {
                    onStartNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    latchStart.countDown();
                }

                @Override
                public void onExit(int statusCode) {
                    exitCode.set(statusCode);
                    onExitNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    latchExit.countDown();
                }

                @Override
                public void onStdout(ByteBuffer buffer, boolean closed) {
                    // don't consume anything, eventually we will be out of space in the buffer
                }
            }, "yes", "spamming the output buffer").start();

            boolean await1 = latchStart.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await1);
            Thread.sleep(100);
            process.destroy(false);
            boolean await2 = latchExit.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await2);
            Assert.assertEquals(2, methodsCallCount.get());
            Assert.assertTrue(onExitNanoTime.get() >= onStartNanoTime.get());
            Assert.assertEquals(0, problems.get());
            Assert.assertTrue(exitCode.get() != 0);
        }
    }
}