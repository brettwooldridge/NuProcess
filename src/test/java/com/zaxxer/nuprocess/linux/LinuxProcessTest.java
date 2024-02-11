package com.zaxxer.nuprocess.linux;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class LinuxProcessTest {

    @Test
    public void exitBeforeEpollRegisterNeedsToWorkFine() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("linux")) {
            return;
        }
        for (int i = 0; i < 1_000; i++) {
            final CountDownLatch latchStart = new CountDownLatch(1);
            final CountDownLatch latchExit = new CountDownLatch(1);
            final AtomicInteger exitCode = new AtomicInteger(-1);
            new NuProcessBuilder(new NuAbstractProcessHandler() {
                @Override
                public void onStart(NuProcess nuProcess) {
                    // originally in LinuxProcess#start and #run the order was
                    // first call registerProcess() then call callStart() method
                    // this is problematic because in some very rare cases, the register will succeed in
                    // registering events to the EPoll, and the EPool will detect process exit and call onExit
                    // from the EPoll processor thread, while the caller thread has just started to call callStart
                    // which calls the handler#onStart from the caller thread
                    // I'd prefer if start was also done from the EPoll thread.
                    latchStart.countDown();
                }

                @Override
                public void onExit(int statusCode) {
                    exitCode.set(statusCode);
                    latchExit.countDown();
                }
            }, "echo", "foo").start();

            boolean await1 = latchStart.await(10, TimeUnit.SECONDS);
            boolean await2 = latchExit.await(10, TimeUnit.SECONDS);
            Assert.assertTrue(await1);
            Assert.assertTrue(await2);
            Assert.assertEquals(0, exitCode.get());
        }
    }
}