NuProcess
=========

A low-overhead, non-blocking I/O, external Process execution implementation for Java.  Think of it as ``java.lang.ProcessBuilder``
and ``java.lang.Process`` on steroids.

Have you ever been annoyed by the fact that for every process you launch in Java there is a ProcessReaper thread created?
And are you even more annoyed by having to create one or two "pumper" threads (for every process) to pull data out of the
``stdout`` and ``stderr`` pipes?  That's a total of three threads per-process (two if you merge stderr w/stdout).

Maybe you've wondered, as I have, why there isn't a single-threaded non-blocking way to do all of this.  Well, now there is.

#### Threads are cheap ####
Threads are "cheap" why does it matter?  First, threads aren't cheap.  There is significant time spent firing up threads,
and under heavy load context-switching overhead, CPU instruction pipeline flushing, and L1/L2/L3 cache-line invalidation
start taking their toll.

If a single thread is allowed to get up-to-speed with little to no context-switching and little to no cache-line 
invalidation, and allowed to take advantage of CPU instruction pipelining and data prefetching, it is actually amazing
what a modern CPU can do.  Many of our attempts at parallelization of processing actually have a paradoxically negative
impact on performance.

