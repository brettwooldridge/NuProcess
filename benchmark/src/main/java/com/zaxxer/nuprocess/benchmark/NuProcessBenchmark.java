package com.zaxxer.nuprocess.benchmark;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.HotspotThreadProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * A simple performance benchmark which uses {@code cat} to write a file to {@code stdout}.
 * <p>
 * This benchmark requires a test file to use. One method to generate a test file is:
 * <code><pre>
 * head -c 250M /dev/urandom >/tmp/random.dat
 * </pre></code>
 * <p>
 * The CRC32 for the file is also required to allow the benchmark to apply a spot check on the contents, after
 * they've been pumped through {@code cat}, to verify that the contents were not corrupted during processing. A
 * CRC32 checksum is used in preference to algorithms like SHA-256 because the speed of the hashing algorithm is
 * not the focus of this benchmark.
 * <p>
 * After generating a test file, you can compute the CRC32 to update the benchmark by either running it once and
 * checking the value in the error message, or running:
 * <code><pre>
 * crc32 /tmp/random.dat
 * </pre></code>
 * That will display the CRC32 checksum in hex, which can then be converted to decimal to match {@code CRC32}'s
 * {@code getValue()}. (The calculator on most operating systems can convert hex to decimal.)
 */
public class NuProcessBenchmark {

    private static final long TEST_CRC32 = 3407529827L;
    private static final String TEST_FILE = "/tmp/random.dat";

    public static void main(String[] args) throws Exception {
        //System.setProperty("com.zaxxer.nuprocess.threads", "cores"); // set when running with forks(0) below

        Options options = new OptionsBuilder()
                .addProfiler(GCProfiler.class)
                .addProfiler(HotspotThreadProfiler.class)
                //.forks(0) // set when running with JProfiler to simplify profiling
                .forks(2)
                //.jvmArgsAppend("-Dcom.zaxxer.nuprocess.threads=cores") // to adjust the pump thread count
                .include(NuProcessBenchmark.class.getSimpleName())
                .measurementIterations(50)
                .threads(10)
                .timeUnit(TimeUnit.SECONDS)
                .warmupIterations(1)
                .build();

        new Runner(options).run();
    }

    @Benchmark
    public void cat() throws Exception {
        NuProcessBuilder builder = new NuProcessBuilder("cat", TEST_FILE);
        builder.setProcessListener(new NuAbstractProcessHandler() {

            private final CRC32 crc32 = new CRC32();

            @Override
            public void onExit(int statusCode) {
                long crc = crc32.getValue();
                if (crc != TEST_CRC32) {
                    System.err.println("Incorrect CRC32 checksum (" + crc + "); file corruption?");
                }
            }

            @Override
            public void onStdout(ByteBuffer buffer, boolean closed) {
                // the contents of the file are run through CRC32 just to "do" something with them
                // note that update(ByteBuffer) requires Java 8. it's been selected because it doesn't
                // inflate the measured allocation rate by allocating buffers internally
                crc32.update(buffer);
            }
        });

        NuProcess process = builder.start();

        int exitCode = process.waitFor(5L, TimeUnit.MINUTES);
        if (exitCode == Integer.MIN_VALUE) {
            process.destroy(false);

            throw new AssertionError(process + " took longer than 5 minutes to complete");
        }
        if (exitCode != 0) {
            throw new AssertionError(process + " failed (Exit code: " + exitCode + ")");
        }
    }
}