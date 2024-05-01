package com.zaxxer.nuprocess.linux;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Need to make sure there are no race conditions.
 */
public class LinuxProcessTest {

    @Test
    public void respectOnStartInRegardsToOnExit() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return;
        }
        for (int i = 0; i < 100; i++) {

            final CountDownLatch latchStart = new CountDownLatch(1);
            final CountDownLatch latchExit = new CountDownLatch(1);
            final AtomicInteger exitCode = new AtomicInteger(-1);
            final AtomicLong onStartNanoTime = new AtomicLong();
            final AtomicLong onExitNanoTime = new AtomicLong();
            final AtomicInteger methodsCallCount = new AtomicInteger();

            new NuProcessBuilder(new NuAbstractProcessHandler() {
                @Override
                public void onStart(NuProcess nuProcess) {
                    onStartNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    nuProcess.wantWrite();
                    latchStart.countDown();
                }

                @Override
                public void onExit(int statusCode) {
                    exitCode.set(statusCode);
                    onExitNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    latchExit.countDown();
                }
            }, "echo", "foo").start();

            boolean await1 = latchStart.await(1, TimeUnit.SECONDS);
            boolean await2 = latchExit.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await1);
            Assert.assertTrue(await2);
            Assert.assertEquals(2, methodsCallCount.get());
            Assert.assertTrue(onExitNanoTime.get() >= onStartNanoTime.get());
            Assert.assertEquals(0, exitCode.get());
        }
    }

    @Test
    public void startShouldBeCalledEvenIfNoEpollEventsHappen() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return;
        }
        for (int i = 0; i < 100; i++) {
            final CountDownLatch latchStart = new CountDownLatch(1);
            final CountDownLatch latchExit = new CountDownLatch(1);
            final AtomicInteger exitCode = new AtomicInteger(-1);
            final AtomicLong onStartNanoTime = new AtomicLong();
            final AtomicLong onExitNanoTime = new AtomicLong();
            final AtomicInteger methodsCallCount = new AtomicInteger();

            NuProcess process = new NuProcessBuilder(new NuAbstractProcessHandler() {
                @Override
                public void onStart(NuProcess nuProcess) {
                    onStartNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    nuProcess.wantWrite();
                    latchStart.countDown();
                }

                @Override
                public void onExit(int statusCode) {
                    exitCode.set(statusCode);
                    onExitNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    latchExit.countDown();
                }

            }, "sleep", "infinity").start();

            boolean await1 = latchStart.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await1);

            process.destroy(false);

            boolean await2 = latchExit.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await2);

            Assert.assertEquals(2, methodsCallCount.get());
            Assert.assertTrue(onExitNanoTime.get() >= onStartNanoTime.get());
            Assert.assertTrue(exitCode.get() != 0);
        }
    }

    @Test
    public void testStdinHandling() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return;
        }
        final byte[] testStringBytes = "TEST STRING\n".getBytes();
        for (int i = 0; i < 100; i++) {
            final CountDownLatch latchStart = new CountDownLatch(1);
            final CountDownLatch latchExit = new CountDownLatch(1);
            final AtomicInteger exitCode = new AtomicInteger(-1);
            final AtomicLong onStartNanoTime = new AtomicLong();
            final AtomicLong onExitNanoTime = new AtomicLong();
            final AtomicInteger methodsCallCount = new AtomicInteger();
            final AtomicInteger problems = new AtomicInteger();
            final AtomicInteger readCount = new AtomicInteger();
            final AtomicInteger wroteCount = new AtomicInteger();

            NuProcess process = new NuProcessBuilder(new NuAbstractProcessHandler() {
                private NuProcess nuProcess;

                @Override
                public void onStart(NuProcess nuProcess) {
                    onStartNanoTime.set(System.nanoTime());
                    methodsCallCount.incrementAndGet();
                    nuProcess.wantWrite();
                    this.nuProcess = nuProcess;
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
                    if (buffer.remaining() > 0) {
                        byte[] dst = new byte[buffer.remaining()];
                        ByteBuffer put = buffer.get(dst);
                        if (!Arrays.equals(dst, testStringBytes)) {
                            problems.incrementAndGet();
                        }
                        readCount.incrementAndGet();
                        // should produce an infinite loop, we exit via calling destroy
                        nuProcess.wantWrite();
                    }
                }

                @Override
                public boolean onStdinReady(ByteBuffer buffer) {
                    buffer.put(testStringBytes);
                    buffer.flip();
                    wroteCount.incrementAndGet();
                    return false;
                }
            }, "cat").start();

            boolean await1 = latchStart.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await1);
            Thread.sleep(100);
            process.destroy(false);
            boolean await2 = latchExit.await(1, TimeUnit.SECONDS);
            Assert.assertTrue(await2);
            Assert.assertEquals(2, methodsCallCount.get());
            Assert.assertTrue(onExitNanoTime.get() >= onStartNanoTime.get());
            Assert.assertEquals(0, problems.get());
            Assert.assertTrue(readCount.get() > 10);
            // it is OK to diff by 1
            Assert.assertEquals(readCount.get(), wroteCount.get(), 1);
            Assert.assertTrue(exitCode.get() != 0);
        }
    }
}