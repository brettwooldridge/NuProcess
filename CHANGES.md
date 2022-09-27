Changes in 2.0.5

 * Fix command line injection vulnerability (CVE-2022-39243) (#143)

Changes in 2.0.4

 * Add `Automatic-Module-Name` to `MANIFEST.MF` to better support Java 9+ modules (#142)

Changes in 2.0.3

 * Add handling for `EINTR` during `epoll_wait` and `ESRCH` from `kill` to prevent zombie processes (#125, #131)
 * Remove static `EpollEvent` queue to avoid blocking under heavy load, which can lead to leaking zombie processes if a blocked thread is interrupted (#138)

Changes in 2.0.2

 * Ensure pipes are closed after failing to start a process on Linux, to prevent leaking descriptors (#120)
 * Reduce pipe buffer size on Windows to increase process throughput to `stdout` and `stderr` (#118)
 * Update JNA dependency to v5.8.0 to pick up support for the Apple M1 (#123)

Changes in 2.0.1

 * Fix race condition between the `onStdinReady()` and `userWantsWrite()` methods that causes the state to
   become incorrect, resulting in failure to continue processing wanted `stdin` writes (#113)

Changes in 2.0.0

 * Update JNA dependency to v5.5.0
 * Add ability to run processes synchronously (#104)
 * Handle detecting Java versions when non-numbers are present (#105)

Changes in 1.2.6

 * Fix linkage issues with Azul JVMs. Special thanks to Bryan Turner for this pull request and extensive testing across
   a wide variety of JVMs. (#107)

Changes in 1.2.5

 * Handle `SystemRoot` case-insensitively (Windows) (#103).
 * Compatibility changes for JNA 5.2.0 (#99).

Changes in 1.2.4

 * Fix structure alignment (`ALIGN_GNUC`) on various platforms (#94).
 * Add support for JDK 10/11. (#92)

Changes in 1.2.3

 * Resolve launch issue on Linux/macOS w/Zulu JVM.

Changes in 1.2.2

 * Fix race condition seen (once) on Linux after moving to JVM internal API for process spawning.

Changes in 1.2.1

* On macOS, continue to use `posix_spawnp` directly, `POSIX_SPAWN_START_SUSPENDED` solves a lot of potential race conditions that we have to contend with on Linux.

Changes in 1.2.0

 * Spawn processes on Linux and macOS using JVM internal method `Java_java_lang_UNIXProcess_forkAndExec`.

Changes in 1.1.3

 * Add constructor with `Pointer` to `LibKevent.Kevent` (#82) that reduces reflection code executed in JNA.
 * Change to Linux `epoll` implementation that massively decreases the amount of memory used.
 * Optimize `epoll_wait` loop to avoid memory churn. (#80)

Changes in 1.1.2

 * Fix issue on macOS where pipes would leak after many calls to `closeStdin`

Changes in 1.1.1

 * Fixed issue where calling `writeStdin` with a buffer larger than 65kb would hang.

Changes in 1.1.0

 * Expose new `NuProcess.getPID()` method on all platforms.
 * Fix wrong position and limit value on `inBuffer` caused by incorrect `inBuffer` clear.
 * Fix source of memory leak and `DirectWriteTest` failure on macOS; clear `wantsWrite` 
   queue on process exit.
 * Remove `System.exit()` call from unit test.
 
Changes in 1.0.4

 * Update OSGi manifest with correct exports.
 * Fix moar Windows argument escaping.
 * Introduce 'force' parameter to `NuProcess.closeStdin()`, and enqueue special
   tombstone marker into the `pendingWrites` queue for non-forced closes.
 * Remove use of `sun.misc.Unsafe`.

Changes in 1.0.3

 * Remove dependency on `jna-platform`.
 * Handle fast-exiting processes on Linux.
 * Fix Windows argument escaping.
 * Handle race condition in named pipe creation on Windows.

Changes in 1.0.2

 * Process `cwd` support for Mac, Linux, and Win32.
 * Remove `ThreadLocal` usage for macOS.

Changes in 1.0.1

 * Performance improvements for macOS `kqueue` / `kevent`.
 * Fix issue where `stdout` and `stderr` are empty for quick exiting processes.

Changes in 1.0.0

 * Genesis.  See the git history for previous evolution.
