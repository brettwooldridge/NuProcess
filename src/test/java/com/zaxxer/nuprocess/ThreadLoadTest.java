package com.zaxxer.nuprocess;

import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Yet another threaded test.
 */
public class ThreadLoadTest {

    @Test
    public void testStartLoad() throws InterruptedException {
        int durationInMs = 15_000;
        long cutOfTime = System.currentTimeMillis() + durationInMs;
        int nrOfThreads = (Runtime.getRuntime().availableProcessors() * 2);
        CountDownLatch latch = new CountDownLatch(nrOfThreads);
        AtomicLong runCountCode0 = new AtomicLong();
        AtomicLong runCountCodeNon0 = new AtomicLong();
        AtomicLong problems = new AtomicLong();
        for (int i = 0; i < nrOfThreads; i++) {
            startNewThread(latch, cutOfTime, runCountCode0, runCountCodeNon0, problems);
        }

        Assert.assertTrue(latch.await(durationInMs + 1_000, TimeUnit.MILLISECONDS));
        System.out.println("runCount 0 = " + runCountCode0.get());
        System.out.println("runCount non-0 = " + runCountCodeNon0.get());
        System.out.println("problems  = " + problems.get());
    }

    private void startNewThread(final CountDownLatch latch, final long cutOfTime, final AtomicLong zeroExit, final AtomicLong nonZeroExit, AtomicLong problems) {
        new Thread(new Runnable() {
            public void run() {
                while (System.currentTimeMillis() < cutOfTime) {
                    final int randomInt = ThreadLocalRandom.current().nextInt(10_000);
                    final String text = "foo" + randomInt;
                    long startTime = System.nanoTime();

                    final NuProcess start = new NuProcessBuilder(new NuAbstractProcessHandler() {
                        public void onPreStart(final NuProcess nuProcess) {
                            super.onPreStart(nuProcess);
                        }

                        @Override
                        public void onStart(NuProcess nuProcess) {
                        }

                        @Override
                        public void onStdout(ByteBuffer buffer, boolean closed) {

                        }

                        public void onExit(final int statusCode) {
                            if (statusCode == 0) {
                                zeroExit.incrementAndGet();
                            } else {
                                nonZeroExit.incrementAndGet();
                            }
                        }
                    }, "echo", text).start();
                    try {
                        start.waitFor(10, TimeUnit.DAYS);

//                        System.out.println("Took " + ((System.nanoTime() - startTime) / 1000000));
//                        start.wantWrite();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }

                latch.countDown();
            }
        }).start();
    }
}
