/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.nuprocess.linux;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;

import static com.zaxxer.nuprocess.internal.LibC.WIFEXITED;
import static com.zaxxer.nuprocess.internal.LibC.WEXITSTATUS;
import static com.zaxxer.nuprocess.internal.LibC.WIFSIGNALED;
import static com.zaxxer.nuprocess.internal.LibC.WTERMSIG;

/**
 * @author Brett Wooldridge
 */
class ProcessEpoll extends BaseEventProcessor<LinuxProcess>
{
   private final int epoll;
   private final EpollEvent triggeredEvent;
   private final List<LinuxProcess> deadPool;

   private LinuxProcess process;

   ProcessEpoll()
   {
      this(LINGER_ITERATIONS);
   }

   ProcessEpoll(LinuxProcess process)
   {
      this(-1);

      this.process = process;

      registerProcess(process);
      checkAndSetRunning();
   }

   private ProcessEpoll(int lingerIterations)
   {
      super(lingerIterations);

      epoll = LibEpoll.epoll_create(1024);
      if (epoll < 0) {
         int errno = Native.getLastError();
         throw new RuntimeException("Unable to create kqueue, errno: " + errno);
      }

      triggeredEvent = new EpollEvent();

      deadPool = new LinkedList<>();
   }

   // ************************************************************************
   //                         IEventProcessor methods
   // ************************************************************************

   @Override
   public void registerProcess(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      int stdinFd = Integer.MIN_VALUE;
      int stdoutFd = Integer.MIN_VALUE;
      int stderrFd = Integer.MIN_VALUE;
      try {
         stdinFd = process.getStdin().acquire();
         stdoutFd = process.getStdout().acquire();
         stderrFd = process.getStderr().acquire();

         pidToProcessMap.put(process.getPid(), process);
         fildesToProcessMap.put(stdinFd, process);
         fildesToProcessMap.put(stdoutFd, process);
         fildesToProcessMap.put(stderrFd, process);

         EpollEvent event = process.getEpollEvent();
         event.setEvents(LibEpoll.EPOLLIN);
         event.setFileDescriptor(stdoutFd);
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdoutFd, event.getPointer());
         if (rc == -1) {
            int errno = Native.getLastError();
            throw new RuntimeException("Unable to register new events to epoll, errno: " + errno);
         }

         event.setEvents(LibEpoll.EPOLLIN);
         event.setFileDescriptor(stderrFd);
         rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stderrFd, event.getPointer());
         if (rc == -1) {
            int errno = Native.getLastError();
            throw new RuntimeException("Unable to register new events to epoll, errno: " + errno);
         }
      }
      finally {
         if (stdinFd != Integer.MIN_VALUE) {
            process.getStdin().release();
         }
         if (stdoutFd != Integer.MIN_VALUE) {
            process.getStdout().release();
         }
         if (stderrFd != Integer.MIN_VALUE) {
            process.getStderr().release();
         }
      }
   }

   @Override
   public void queueWrite(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      try {
         int stdin = process.getStdin().acquire();
         if (stdin == -1) {
           return;
         }

         EpollEvent event = process.getEpollEvent();
         event.setEvents(LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP);
         event.setFileDescriptor(stdin);
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, stdin, event.getPointer());
         if (rc == -1) {
            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, event.getPointer());
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdin, event.getPointer());
            if (rc == -1) {
               int errno = Native.getLastError();
               throw new RuntimeException("Unable to register new event to epoll queue, errno: " + errno);
            }
         }
      }
      finally {
         process.getStdin().release();
      }
   }

   @Override
   public void run()
   {
      super.run();

      if (process != null) {
         // For synchronous execution, wait until the deadpool is drained. This is necessary to ensure
         // the handler's onExit is called before LinuxProcess.run returns.
         waitForDeadPool();
      }
   }

   @Override
   public void closeStdin(LinuxProcess process)
   {
      try {
         int stdin = process.getStdin().acquire();
         if (stdin != -1) {
            fildesToProcessMap.remove(stdin);
            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, null);
         }
      } finally {
         process.getStdin().release();
      }
   }

   @Override
   public boolean process()
   {
      int stdinFd = Integer.MIN_VALUE;
      int stdoutFd = Integer.MIN_VALUE;
      int stderrFd = Integer.MIN_VALUE;
      LinuxProcess linuxProcess = null;
      try {
         int nev = LibEpoll.epoll_wait(epoll, triggeredEvent.getPointer(), 1, DEADPOOL_POLL_INTERVAL);
         if (nev == -1) {
            int errno = Native.getLastError();
            if (errno == LibC.EINTR) {
               // Signals received while in epoll_wait can interrupt the call and, per the documentation for
               // SA_RESTART, it will not be restarted automatically. When that happens, we manually restart
               // epoll_wait by returning true, which ensures BaseEventProcessor.run() calls process() again.
               // This matches how the JVM internals handle EINTR during epoll_wait.
               // See https://github.com/JetBrains/jdk8u_jdk/blob/94318f9185757cc33d2b8d527d36be26ac6b7582/src/solaris/native/sun/nio/ch/nio_util.h#L33-L37
               return true;
            }

            throw new RuntimeException("Error waiting for epoll, errno: " + errno);
         }

         if (nev == 0) {
            return false;
         }

         EpollEvent epEvent = triggeredEvent;
         int ident = epEvent.getFileDescriptor();
         int events = epEvent.getEvents();

         linuxProcess = fildesToProcessMap.get(ident);
         if (linuxProcess == null) {
            return true;
         }

         stdinFd = linuxProcess.getStdin().acquire();
         stdoutFd = linuxProcess.getStdout().acquire();
         stderrFd = linuxProcess.getStderr().acquire();

         if ((events & LibEpoll.EPOLLIN) != 0) { // stdout/stderr data available to read
            if (ident == stdoutFd) {
               linuxProcess.readStdout(NuProcess.BUFFER_CAPACITY, stdoutFd);
            }
            else if (ident == stderrFd) {
               linuxProcess.readStderr(NuProcess.BUFFER_CAPACITY, stderrFd);
            }
         }
         else if ((events & LibEpoll.EPOLLOUT) != 0) { // Room in stdin pipe available to write
            if (stdinFd != -1) {
               if (linuxProcess.writeStdin(NuProcess.BUFFER_CAPACITY, stdinFd)) {
                  epEvent.setEvents(LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP);
                  LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, ident, epEvent.getPointer());
               }
            }
         }

         if ((events & LibEpoll.EPOLLHUP) != 0 || (events & LibEpoll.EPOLLRDHUP) != 0 || (events & LibEpoll.EPOLLERR) != 0) {
            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, ident, null);
            if (ident == stdoutFd) {
               linuxProcess.readStdout(-1, stdoutFd);
            }
            else if (ident == stderrFd) {
               linuxProcess.readStderr(-1, stderrFd);
            }
            else if (ident == stdinFd) {
               linuxProcess.closeStdin(true);
            }
         }

         if (linuxProcess.isSoftExit()) {
            cleanupProcess(linuxProcess, stdinFd, stdoutFd, stderrFd);
         }

         return true;
      }
      finally {
         if (linuxProcess != null) {
            if (stdinFd != Integer.MIN_VALUE) {
               linuxProcess.getStdin().release();
            }
            if (stdoutFd != Integer.MIN_VALUE) {
               linuxProcess.getStdout().release();
            }
            if (stderrFd != Integer.MIN_VALUE) {
               linuxProcess.getStderr().release();
            }
         }
         checkDeadPool();
      }
   }

   /**
    * Closes the {@code eventpoll} file descriptor.
    *
    * @since 1.3
    */
   @Override
   protected void close()
   {
      LibC.close(epoll);
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   private void cleanupProcess(LinuxProcess linuxProcess, int stdinFd, int stdoutFd, int stderrFd)
   {
      pidToProcessMap.remove(linuxProcess.getPid());
      fildesToProcessMap.remove(stdinFd);
      fildesToProcessMap.remove(stdoutFd);
      fildesToProcessMap.remove(stderrFd);

      //        linuxProcess.close(linuxProcess.getStdin());
      //        linuxProcess.close(linuxProcess.getStdout());
      //        linuxProcess.close(linuxProcess.getStderr());

      if (linuxProcess.cleanlyExitedBeforeProcess.get()) {
         linuxProcess.onExit(0);
         return;
      }

      IntByReference ret = new IntByReference();
      int rc = LibC.waitpid(linuxProcess.getPid(), ret, LibC.WNOHANG);

      if (rc == 0) {
         deadPool.add(linuxProcess);
      }
      else if (rc < 0) {
         linuxProcess.onExit((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
      }
      else {
         handleExit(linuxProcess, ret.getValue());
      }
   }

   private void checkDeadPool()
   {
      if (deadPool.isEmpty()) {
         return;
      }

      IntByReference ret = new IntByReference();
      Iterator<LinuxProcess> iterator = deadPool.iterator();
      while (iterator.hasNext()) {
         LinuxProcess process = iterator.next();
         int rc = LibC.waitpid(process.getPid(), ret, LibC.WNOHANG);
         if (rc == 0) {
            continue;
         }

         iterator.remove();
         if (rc < 0) {
            process.onExit((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            continue;
         }

         handleExit(process, ret.getValue());
      }
   }

   private void handleExit(final LinuxProcess process, int status)
   {
      if (WIFEXITED(status)) {
         status = WEXITSTATUS(status);
         if (status == 127) {
            process.onExit(Integer.MIN_VALUE);
         }
         else {
            process.onExit(status);
         }
      }
      else if (WIFSIGNALED(status)) {
         process.onExit(WTERMSIG(status));
      }
      else {
         process.onExit(Integer.MIN_VALUE);
      }
   }

   /**
    * Loops until the {@link #deadPool} is empty, using a backoff sleep to progressively increase the poll
    * interval the longer the process takes to terminate.
    * <p>
    * {@code waitpid} does not offer a timeout variant. Callers have two options:
    * <ul>
    *     <li>Use {@code WNOHANG} and have the call return immediately, whether the process has terminated
    *     or not, and use the return code to tell the difference</li>
    *     <li>Don't use {@code WNOHANG} and have the call block until the process terminates</li>
    * </ul>
    * To avoid the possibility of a misbehaving process hanging the JVM indefinitely, this loop uses a Java-
    * based sleep to wait between checks. The sleep interval ramps up each time the loop runs. The ramp-up
    * is intended to minimize the penalty imposed on well-behaved processes, which will generally only loop
    * once or twice before terminating.
    * <p>
    * This loop will wait up to the {@link #LINGER_TIME_MS configured linger timeout} for the process to
    * terminate. At that point, if the process still hasn't terminated, it is abandoned.
    */
   private void waitForDeadPool()
   {
      long sleepInterval = 0L;
      long timeout = System.currentTimeMillis() + LINGER_TIME_MS;
      while (true) {
         checkDeadPool();
         if (deadPool.isEmpty() || System.currentTimeMillis() > timeout) {
            break;
         }

         if (sleepInterval > 0L) { // This gives 2 checks in a row before the first sleep
            try {
               Thread.sleep(sleepInterval);
            }
            catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }
         }
         // 0 -> 1 -> 3 -> 7 -> 15 -> 31 -> 63 -> 127, etc
         sleepInterval = (sleepInterval * 2L) + 1L;
      }
   }
}
