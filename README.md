NuProcess
=========

[![][Build Status img]][Build Status]
[![][Shippable Status img]][Shippable Status]
[![][license img]][license]
[![][Maven Central img]][Maven Central]
[![][Javadocs img]][Javadocs]

A low-overhead, non-blocking I/O, external Process execution implementation for Java.  It is a replacement for
``java.lang.ProcessBuilder`` and ``java.lang.Process``.

Have you ever been annoyed by the fact that whenever you spawn a process in Java you have to create two or three "pumper"
threads (for every process) to pull data out of the ``stdout`` and ``stderr`` pipes and pump data into ``stdin``?  If your
code starts a lot of processes you can have dozens or hundreds of threads doing nothing but pumping data.

NuProcess uses the JNA library to use platform-specific native APIs to achieve non-blocking I/O on the pipes between your
Java process and the spawned processes:

 * Linux: uses epoll
 * MacOS X: uses kqueue/kevent
 * Windows: uses IO Completion Ports

#### Maven
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>nuprocess</artifactId>
        <version>2.0.6</version>
        <scope>compile</scope>
    </dependency>

:point_right: **Note: Versions 1.0.0 and above contain breaking API changes from 0.9.7.**

#### It's mostly about the memory
Speed-wise, there is not a significant difference between NuProcess and the standard Java Process class, even when running
500 concurrent processes.  On some platforms such as MacOS X or Linux, NuProcess is 20% faster than ``java.lang.Process``
for large numbers of processes.

However, when it comes to memory there is a significant difference.  The overhead of 500 threads, for example, is quite
large compared to the one or few threads employed by NuProcess.

Additionally, on unix-based platforms such as Linux, when creating a new process ``java.lang.Process`` uses a fork()/exec()
operation.  This requires a temporary copy of the Java process (the fork), before the exec is performed.  When running
tests on Linux, in order to spawn 500 processes required setting the JVM max. memory to 3Gb (-Xmx3g).  NuProcess uses a
variant of ``fork()`` called ``vfork()``, which does not impose this overhead.  NuProcess can comfortably spawn 500 processes
even when running the JVM with only 128Mb.

#### Example
Like the Java ``ProcessBuilder``, NuProcess offers ``NuProcessBuilder``, so building a process is fairly simple.  Let's make a simple example where we use the Unix "cat" command.  When launched with no parameters, *cat* reads from STDIN and echos the output to STDOUT.  We're going to start the *cat* process, write *"Hello world!"* to its STDIN, and read the echoed reply from STDOUT and print it.  Let's build and start the process.
```java
NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"));
ProcessHandler handler = new ProcessHandler();
pb.setProcessListener(handler);
NuProcess process = pb.start();
process.wantWrite();
process.waitFor(0, TimeUnit.SECONDS); // when 0 is used for waitFor() the wait is infinite
```
You'll notice the ``ProcessHandler`` in code above.  This is a class you provide which receives callbacks from the process to handle input, output, termination, etc.  And notice the ``wantWrite()`` call, this expresses that we have something we want to write to the process, so our ``ProcessHandler`` will be called back to perform the write.  Here's what ``ProcessHandler`` looks like for our example:
```java
class ProcessHandler extends NuAbstractProcessHandler {
   private NuProcess nuProcess;

   @Override
   public void onStart(NuProcess nuProcess) {
      this.nuProcess = nuProcess;
   }
   
   @Override
   public boolean onStdinReady(ByteBuffer buffer) {
      buffer.put("Hello world!".getBytes());
      buffer.flip();
      return false; // false means we have nothing else to write at this time
   }

   @Override
   public void onStdout(ByteBuffer buffer, boolean closed) {
      if (!closed) {
         byte[] bytes = new byte[buffer.remaining()];
         // You must update buffer.position() before returning (either implicitly,
         // like this, or explicitly) to indicate how many bytes your handler has consumed.
         buffer.get(bytes);
         System.out.println(new String(bytes));

         // For this example, we're done, so closing STDIN will cause the "cat" process to exit
         nuProcess.closeStdin(true);
      }
   }
}
```

#### Synchronous Operation
NuProcess does allow you to perform synchronous *writes* to the *stdin* of the spawned process.  Even though the writes are *synchronous* they are *non-blocking*; meaning the write returns immediately.  In this model, you do not use ``NuProcess.wantWrite()`` and your ``onStdinReady()`` method will not be called.  If you extend the ``NuAbstractProcessHandler`` you do not need to provide an implementation of ``onStdinReady()``.  Use the ``NuProcess.writeStdin()`` method to write data to the process.  This method will return immediately and writes are queued and occur in order.  Read the JavaDoc for the ``NuProcess.writeStdin()`` method for cautions and caveats.

In the synchronous model, the above example would look like this:
```java
NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("/bin/cat"));
ProcessHandler handler = new ProcessHandler();
pb.setProcessListener(handler);
NuProcess process = pb.start();

ByteBuffer buffer = ByteBuffer.wrap("Hello, World!".getBytes());
process.writeStdin(buffer);

process.waitFor(0, TimeUnit.SECONDS); // when 0 is used for waitFor() the wait is infinite
```

And the handler:
```java
class ProcessHandler extends NuAbstractProcessHandler {
   private NuProcess nuProcess;

   @Override
   public void onStart(NuProcess nuProcess) {
      this.nuProcess = nuProcess;
   }
   
   public void onStdout(ByteBuffer buffer, boolean closed) {
      if (!closed) {
         byte[] bytes = new byte[buffer.remaining()];
         // You must update buffer.position() before returning (either implicitly,
         // like this, or explicitly) to indicate how many bytes your handler has consumed.
         buffer.get(bytes);
         System.out.println(new String(bytes));

         // For this example, we're done, so closing STDIN will cause the "cat" process to exit
         nuProcess.closeStdin(true);
      }
   }
}
```

#### JavaDocs
You can read the [JavaDoc here](http://brettwooldridge.github.io/NuProcess/apidocs/index.html).  Make sure you read and fully understand the JavaDoc for the ``NuProcessHandler`` interface as it is your primary contract with NuProcess.

#### Settings
These are settings that can be defined as System properties that control various behaviors of the NuProcess library.  *You typically do not need to modify these.*

##### ``com.zaxxer.nuprocess.threads``
This setting controls how many threads are used to handle the STDIN, STDOUT, STDERR streams of spawned processes.  No
matter how many processes are spawned, this setting will be the *maximum* number of threads used.  Possible values are:

 * ``auto`` (default) - this sets the *maximum* number of threads to the number of CPU cores divided by 2.
 * ``cores`` - this sets the *maximum* number of threads to the number of CPU cores.
 * ``<number>`` - the sets the *maximum* number of threads to a specific number.  Often ``1`` will provide good performance even for dozens of processes.

The default is ``auto``, but in reality if your child processes are "bursty" in their output, rather than producing a
constant stream of data, a single thread may provide equivalent performance even with hundreds of processes.

##### ``com.zaxxer.nuprocess.softExitDetection``
On Linux and Windows there is no method by which you can be notified in an asynchronous manner that a child process has
exited.  Rather than polling all child processes constantly NuProcess uses what we call "Soft Exit Detection".  When a
child process exits, the OS automatically closes all of it's open file handles; which *is* something about which we can be
notified.  So, on Linux and Windows when NuProcess determines that both the STDOUT and STDERR streams have been closed
in the child process, that child process is put into a "dead pool".  The processes in the dead pool are polled to 
determine when they have truly exited and what their exit status was.  See ``com.zaxxer.nuprocess.deadPoolPollMs``

The default value for this property is ``true``.  Setting this value to ``false`` will completely disable process exit 
detection, and the ``NuProcess.waitFor()" API __MUST__ be used.  Failure to invoke this API on Linux will result in an 
ever-growing accumulation of "zombie" processes and eventually an inability to create new processes.  There is very little
reason to disable soft exit detection unless you have child process that itself closes the STDOUT and STDERR streams.

##### ``com.zaxxer.nuprocess.deadPoolPollMs``
On Linux and Windows, when Soft Exit Detection is enabled (the default), this property controls how often the processes in
the dead pool are polled for their exit status.  The default value is 250ms, and the minimum value is 100ms.

##### ``com.zaxxer.nuprocess.lingerTimeMs``
This property controls how long the processing thread(s) remains after the last executing child process has exited.  In
order to avoid the overhead of starting up another processing thread, if processes are frequently run it may be desirable
for the processing thread to remain (linger) for some amount of time (default 2500ms).

#### Related Projects
Charles Duffy has developed a Clojure wrapper library [here](https://github.com/threatgrid/asynp).
Julien Viet has developed a Vert.x 3 library [here](https://github.com/vietj/vertx-childprocess).

#### Limitations
The following limitations exist in NuProcess:
 * Currently only supports Linux, Windows, and MacOS X.
 * Java 7 and above
 * Linux support requires at least kernel version 2.6.17 or higher (kernels after June 2006)

[Build Status]:https://circleci.com/gh/brettwooldridge/NuProcess`
[Build Status img]:https://circleci.com/gh/brettwooldridge/NuProcess.svg?style=shield

[Shippable Status]:https://app.shippable.com/github/brettwooldridge/NuProcess
[Shippable Status img]:https://api.shippable.com/projects/5b4b6a3e6db3b807000a63d8/badge?branch=master

[license]:LICENSE
[license img]:https://img.shields.io/badge/license-Apache%202-blue.svg
   
[Maven Central]:https://maven-badges.herokuapp.com/maven-central/com.zaxxer/nuprocess
[Maven Central img]:https://maven-badges.herokuapp.com/maven-central/com.zaxxer/nuprocess/badge.svg
   
[Javadocs]:http://javadoc.io/doc/com.zaxxer/nuprocess
[Javadocs img]:http://javadoc.io/badge/com.zaxxer/nuprocess.svg
