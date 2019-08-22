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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

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
   private static final int EVENT_POOL_SIZE = 64;
   private static final BlockingQueue<EpollEvent> eventPool;

   private int epoll;
   private EpollEvent triggeredEvent;
   private List<LinuxProcess> deadPool;
   private LinuxProcess process;

   static
   {
      eventPool = new ArrayBlockingQueue<>(EVENT_POOL_SIZE);
      for (int i = 0; i < EVENT_POOL_SIZE; i++) {
         EpollEvent event = new EpollEvent();
         eventPool.add(event);
      }
   }

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
         throw new RuntimeException("Unable to create kqueue: " + Native.getLastError());
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

         try {
            EpollEvent event = eventPool.take();
            event.setEvents(LibEpoll.EPOLLIN);
            event.setFileDescriptor(stdoutFd);
            int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdoutFd, event.getPointer());
            if (rc == -1) {
               rc = Native.getLastError();
               eventPool.put(event);
               throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
            }
            eventPool.put(event);

            event = eventPool.take();
            event.setEvents(LibEpoll.EPOLLIN);
            event.setFileDescriptor(stderrFd);
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stderrFd, event.getPointer());
            if (rc == -1) {
               rc = Native.getLastError();
               eventPool.put(event);
               throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
            }
            eventPool.put(event);
         }
         catch (InterruptedException ie) {
            throw new RuntimeException(ie);
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
         EpollEvent event = eventPool.take();
         event.setEvents(LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP);
         event.setFileDescriptor(stdin);
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, stdin, event.getPointer());
         if (rc == -1) {
            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, event.getPointer());
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdin, event.getPointer());
         }

         eventPool.put(event);
         if (rc == -1) {
            throw new RuntimeException("Unable to register new event to epoll queue");
         }
      }
      catch (InterruptedException ie) {
         throw new RuntimeException(ie);
      }
      finally {
         process.getStdin().release();
      }
   }

   @Override
   public void run() {
      super.run();

      if (process != null) {
         // For synchronous execution, perform a final _blocking_ deadpool check. If the deadpool
         // is empty, this will return immediately. If the process has already exited, waitpid will
         // return immediately. Otherwise, this will block until the the process terminates. This
         // is necessary to ensure the handler's onExit is called before LinuxProcess.run returns.
         checkDeadPool(0);
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
            throw new RuntimeException("Error waiting for epoll");
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
         checkDeadPool(LibC.WNOHANG);
      }
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

   private void checkDeadPool(int options)
   {
      if (deadPool.isEmpty()) {
         return;
      }

      IntByReference ret = new IntByReference();
      Iterator<LinuxProcess> iterator = deadPool.iterator();
      while (iterator.hasNext()) {
         LinuxProcess process = iterator.next();
         int rc = LibC.waitpid(process.getPid(), ret, options);
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
}
