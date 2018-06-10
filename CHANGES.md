
Changes in 1.2.3
 * Resolve launch issue on Linux/MacOS X w/Zulu JVM.

Changes in 1.2.2
 * Fix race condition seen (once) on Linux after moving to JVM internal API for process spawning.

Changes in 1.2.1
 * On OS X, continue to use posix_spawnp directly, POSIX_SPAWN_START_SUSPENDED solves a lot of potential race conditions that we have to contend with on Linux.

Changes in 1.2.0
 * Spawn processes on Linux and MacOS X using JVM internal method Java_java_lang_UNIXProcess_forkAndExec.

Changes in 1.1.3

 * Add constructor with Pointer to LibKevent.Kevent (#82) that reduces reflection code executed in JNA.

 * Change to Linux epoll implementation that massively decreases the amount of memory used.
 
 * Optimize epoll_wait loop to avoid memory churn. (#80)

Changes in 1.1.2

 * Fix issue on Mac OS X where pipes would leak after many calls to closeStdin

Changes in 1.1.1

 * Fixed issue where calling writeStdin with a buffer larger than 65kb would hang.

Changes in 1.1.0

 * Expose new NuProcess.getPID() method on all platforms.

 * Fix wrong position and limit value on inBuffer caused by incorrect inBuffer clear.

 * Fix source of memory leak and DirectWriteTest failure on MacOS X; clear wantsWrite 
   queue on process exit.

 * Remove System.exit() call from unit test.
 
Changes in 1.0.4

 * Update OSGi manifest with correct exports.

 * Fix moar Windows argument escaping.

 * Introduce 'force' parameter to NuProcess.closeStdin(), and enqueue special
   tombstone marker into the pendingWrites queue for non-forced closes.

 * Remove use of sun.misc.Unsafe.

Changes in 1.0.3

 * Remove dependency on jna-platform.

 * Handle fast-exiting processes on Linux.

 * Fix Windows argument escaping.

 * Handle race condition in named pipe creation on Windows.

Changes in 1.0.2

 * Process cwd support for Mac, Linux, and Win32.

 * Remove ThreadLocal usage for MacOS X.

Changes in 1.0.1

 * Performance improvements for OS X kqueue / kevent.

 * Fix issue where stdout and stderr are empty for quick exiting processes.

Changes in 1.0.0

 * Genesis.  See the git history for previous evolution.
