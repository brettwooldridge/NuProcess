NuProcess
=========

A low-overhead, non-blocking I/O, external Process execution implementation for Java.  Think of it as ``java.lang.ProcessBuilder``
and ``java.lang.Process`` on steroids.

Have you ever been annoyed by the fact that whenever you spawn a process in Java there is a ProcessReaper thread created?
And are you even more annoyed by having to create two or three "pumper" threads (for every process) to pull data out of the
``stdout`` and ``stderr`` pipes and pump data into ``stdin``?  That's a total of three threads per-process + 1 reaper!

Maybe you've wondered, as I have, why there isn't a single-threaded non-blocking way to do all of this.  **Well, now there is.**

#### Threads are cheap, right? ####
Threads are "cheap" so why does it matter?  Well, sorry to break the bad news but threads aren't cheap.  There is 
significant time spent firing up threads, and under heavy load context-switching overhead, CPU instruction pipeline
flushing, and L1/L2/L3 cache-line invalidation start taking their toll.

But if a single thread is allowed to get up-to-speed with little to no context-switching and little to no cache-line 
invalidation, and allowed to take advantage of CPU instruction pipelining and data prefetching, you'll be amazed at what
what a modern CPU can do.  Many of our attempts at parallelization of processing actually have a paradoxically negative
impact on performance.

#### What kinda speed are we talking? ####
Well, if course it depends on your workload, but let's take an example.  Artifical though it may be, it likely stresses
the system more than what you might throw at it.

Take the ``/bin/cat`` process in Unix (Linux, OS X, etc.).  If you launch it without any parameters, it reads whatever
you feed into ``stdin`` and spits it back out of ``stdout``.  Perfect for testing.

Let's say for fun we want to spawn 50 instances of ``/bin/cat`` at one time, pump 600K of data into *each* one (stdin) 
while simultaneously reading the data out (stdout).  Actually, we don't have a choice about the reading otherwise the
output pipe would fill up and the processes would stall.  When they're done, we'll do it another 99 times.  So to recap,
in total we're going to start 5000 ``/bin/cat`` processes (in batches of 50), pump 600K of data into each and every one 
while pulling all the data out the other side.  Oh and running an Adler32 checksum to verify that everything went through
okay.

##### Old school #####
The conventional approach is to create a thread for pumping data in (stdin) and a thread for pumping data out (stdout).
We actually need 50 of these (each) if we really want to run 50 processes in parallel.  Using a ``ThreadPoolExecutor``
for each set of threads (one executor for stdin-threads and one for stdout-threads), we can at least cut the thread launch
overhead on the last 99 iterations of our 100.  Still we're looking at 100 threads and no real way to get around it.
Other than that, it's basically trivial, and you can see the code in ``src/example/java/OldSchool.java``.

##### Nu school #####
