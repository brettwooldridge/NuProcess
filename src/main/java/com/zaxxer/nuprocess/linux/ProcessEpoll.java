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
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcess.Stream;
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

   private final Logger LOGGER = LoggerFactory.getLogger(ProcessEpoll.class);

   private int epoll;
   private EpollEvent triggeredEvent;
   private List<LinuxProcess> deadPool;

   static
   {
      eventPool = new ArrayBlockingQueue<EpollEvent>(EVENT_POOL_SIZE);
      for (int i = 0; i < EVENT_POOL_SIZE; i++) {
         eventPool.add(new EpollEvent());
      }
   }

   ProcessEpoll()
   {
      epoll = LibEpoll.epoll_create(1024);
      if (epoll < 0) {
         throw new RuntimeException("Unable to create kqueue: " + Native.getLastError());
      }

      triggeredEvent = new EpollEvent();
      deadPool = new LinkedList<LinuxProcess>();
   }

   // ************************************************************************
   //                         IEventProcessor methods
   // ************************************************************************

   @Override
   public synchronized void registerProcess(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      int stdin  = process.getStdin().get();
      int stdout = process.getStdout().get();
      int stderr = process.getStderr().get();

      pidToProcessMap.put(process.getKey(stdin), process);
      pidToProcessMap.put(process.getKey(stdout), process);
      pidToProcessMap.put(process.getKey(stderr), process);

      // In order for soft-exit detection to work, we must allways be listening for HUP/ERR on
      // stdout and stderr.
      EpollEvent epEvent = new EpollEvent();
      epEvent.events = LibEpoll.EPOLLHUP | LibEpoll.EPOLLERR;
      epEvent.data.u64 = process.getKey(stdout);
      LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdout, epEvent);
      LOGGER.debug("action(EPOLL_CTL_ADD) EPOLLHUP | EPOLLERR on stdout({}) for {} with ident {}", stdout, process, process.getKey(stdout));

      EpollEvent epEvent2 = new EpollEvent();
      epEvent2.events = LibEpoll.EPOLLHUP | LibEpoll.EPOLLERR;
      epEvent2.data.u64 = process.getKey(stderr);
      LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stderr, epEvent2);
      LOGGER.debug("action(EPOLL_CTL_ADD) EPOLLHUP | EPOLLERR on stderr({}) for {} with ident {}", stderr, process, process.getKey(stderr));
   }

   @Override
   public synchronized void queueRead(LinuxProcess process, Stream stream)
   {
      final int fd;
      switch (stream) {
         case STDOUT:
            fd = process.getStdout().get();
            break;
         case STDERR:
            fd = process.getStderr().get();
            break;
         default:
            throw new IllegalArgumentException(stream.name() + " is not a valid Stream for queueRead()");
      }

      EpollEvent event = null;
      try {
         event = eventPool.take();
         event.events = LibEpoll.EPOLLIN | LibEpoll.EPOLLONESHOT;
         event.data.u64 = process.getKey(fd);
         //synchronized (process)
         {
	         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, fd, event);
	         if (rc == -1) {
	            rc = Native.getLastError();
	            if (rc == LibEpoll.ENOENT) {
	               rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, fd, event);
	               LOGGER.debug("queueRead() : action(EPOLL_CTL_ADD) EPOLLIN | EPOLLONESHOT on {}({}) for {}", stream, fd, process);
	            }
//	            else {
//	               throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
//	            }
	         }
	         else {
	            LOGGER.debug("queueRead() 2: action(EPOLL_CTL_MOD) EPOLLIN | EPOLLONESHOT on {}({}) for {}", stream, fd, process);
	         }
         }
      }
      catch (Exception ie) {
         throw new RuntimeException(ie);
      }  
      finally {
         if (event != null) {
            eventPool.offer(event);
         }
      }
   }

   @Override
   public synchronized void queueWrite(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      int stdin = process.getStdin().get();
      if (stdin == -1) {
        return;
      }

      EpollEvent event = null;
      try {
         event = eventPool.take(); // new EpollEvent();
         event.events = LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLHUP ; // | LibEpoll.EPOLLRDHUP;
         event.data.u64 = process.getKey(stdin);
         // int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, stdin, event);
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdin, event);
         if (rc == -1) {
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, event);
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdin, event);
            LOGGER.debug("queueWrite(): action(EPOLL_CTL_ADD) EPOLLOUT | EPOLLONESHOT | EPOLLRDHUP | EPOLLHUP on stdin({}) for {} with ident {}", stdin, process, process.getKey(stdin));
         }
         else {
            LOGGER.debug("queueWrite 2(): action(EPOLL_CTL_ADD) EPOLLOUT | EPOLLONESHOT | EPOLLRDHUP | EPOLLHUP on stdin({}) for {} with ident {}", stdin, process, process.getKey(stdin));
         }

         if (rc == -1) {
            rc = Native.getLastError();
            throw new RuntimeException("Unable to register new event to epoll queue");
         }
      }
      catch (Exception ie) {
         throw new RuntimeException(ie);
      }
      finally {
         if (event != null) {
            eventPool.offer(event);
         }
      }
   }

   @Override
   public synchronized void closeStdin(LinuxProcess process)
   {
      int stdin = process.getStdin().getAndSet(-1);
      if (stdin != -1) {
         // pidToProcessMap.remove(process.getKey(stdin));
         // LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, null);  -- everything is oneshot, so this should be removed automatically
      }
   }

   @Override
   public boolean process()
   {
      try {
         int nev = LibEpoll.epoll_wait(epoll, triggeredEvent, 1, DEADPOOL_POLL_INTERVAL);
         if (nev == -1) {
            throw new RuntimeException("Error waiting for epoll");
         }

         if (nev == 0) {
            return false;
         }

         EpollEvent epEvent = triggeredEvent;
         long ident = epEvent.data.u64;
         int events = epEvent.events;
         int fd = (int) (ident & 0xffffffffL);
         int uid = (int) ((ident >>> 32) & 0xffffffffL);

         LinuxProcess linuxProcess = pidToProcessMap.get(ident);
         if (linuxProcess == null) {
            return true;
         }

         // int fd = linuxProcess.getFdFromKey(ident);
         synchronized (this)
         {
	         if ((events & LibEpoll.EPOLLIN) != 0) // stdout/stderr data available to read
	         {
	            boolean again = false;
	            if (fd == linuxProcess.getStdout().get()) {
	               again = linuxProcess.readStdout(NuProcess.BUFFER_CAPACITY);
	            }
	            else {
	               again = linuxProcess.readStderr(NuProcess.BUFFER_CAPACITY);
	            }
	
	            EpollEvent newEvent = new EpollEvent();
	            newEvent.data.u64 = ident;
	            newEvent.events = LibEpoll.EPOLLHUP | LibEpoll.EPOLLERR;
	            if (again) {
	               newEvent.events = newEvent.events | LibEpoll.EPOLLIN | LibEpoll.EPOLLONESHOT;
	               LOGGER.debug("process(): action(EPOLL_CTL_MOD) EPOLLIN | EPOLLONESHOT | EPOLLHUP | EPOLLERR on stdout/err({}) for {}", fd, linuxProcess);
	            }
	            else {
	                 LOGGER.debug("process(): action(EPOLL_CTL_MOD) EPOLLHUP | EPOLLERR on stdout/err({}) for {}", fd, linuxProcess);
	            }
	            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, fd, newEvent);
	         }
	         else if ((events & LibEpoll.EPOLLOUT) != 0) // Room in stdin pipe available to write
	         {
	            if (linuxProcess.getStdin().get() == fd) {
	               if (linuxProcess.writeStdin(NuProcess.BUFFER_CAPACITY)) {
	                  EpollEvent newEvent = new EpollEvent();
	                  newEvent.data.u64 = ident;

	                  newEvent.events = LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP;
	                  int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, fd, newEvent);
	                  if (rc == -1) {
	                     rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, fd, newEvent);
	                     rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, fd, newEvent);
	                  }
	               }
	            }
	         }
	         else if ((events & LibEpoll.EPOLLHUP) != 0 || (events & LibEpoll.EPOLLRDHUP) != 0 || (events & LibEpoll.EPOLLERR) != 0) {
	            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, fd, null);
	            if (fd == linuxProcess.getStdout().get()) {
                  LOGGER.debug("process(): Received EPOLLHUP on stdout({}) for {}", fd, linuxProcess);
	               linuxProcess.readStdout(-1);
	            }
	            else if (fd == linuxProcess.getStderr().get()) {
                  LOGGER.debug("process(): Received EPOLLHUP on stderr({}) for {}", fd, linuxProcess);
	               linuxProcess.readStderr(-1);
	            }
	            else if (fd == linuxProcess.getStdin().get()) {
                  LOGGER.debug("process(): Received EPOLLHUP on stdin({}) for {}", fd, linuxProcess);
	               linuxProcess.closeStdin(true);
	            }
	         }
         }

         if (linuxProcess.isSoftExit()) {
            cleanupProcess(linuxProcess);
         }

         return true;
      }
      finally {
         triggeredEvent.clear();
         checkDeadPool();
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************
   AtomicInteger count = new AtomicInteger();

   private void cleanupProcess(LinuxProcess linuxProcess)
   {
      pidToProcessMap.remove(linuxProcess.getKey(linuxProcess.getStdin().get()));
      pidToProcessMap.remove(linuxProcess.getKey(linuxProcess.getStdout().get()));
      pidToProcessMap.remove(linuxProcess.getKey(linuxProcess.getStderr().get()));

//      fildesToProcessMap.remove(linuxProcess.getStdin().get());
//      fildesToProcessMap.remove(linuxProcess.getStdout().get());
//      fildesToProcessMap.remove(linuxProcess.getStderr().get());

      //        linuxProcess.close(linuxProcess.getStdin());
      //        linuxProcess.close(linuxProcess.getStdout());
      //        linuxProcess.close(linuxProcess.getStderr());

      if (linuxProcess.cleanlyExitedBeforeProcess.get()) {
         linuxProcess.onExit();
         return;
      }

      IntByReference ret = new IntByReference();
      int rc = LibC.waitpid(linuxProcess.getPid(), ret, LibC.WNOHANG);

      if (rc == 0) {
         deadPool.add(linuxProcess);
      }
      else if (rc < 0) {
         linuxProcess.setExitCode((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
         linuxProcess.onExit();
      }
      else {
         int status = ret.getValue();
         if (WIFEXITED(status)) {
            status = WEXITSTATUS(status);
            if (status == 127) {
               linuxProcess.setExitCode(Integer.MIN_VALUE);
            }
            else {
               linuxProcess.setExitCode(status);
            }
         }
         else if (WIFSIGNALED(status)) {
            linuxProcess.setExitCode(WTERMSIG(status));
         }
         else {
            linuxProcess.setExitCode(Integer.MIN_VALUE);
         }

         linuxProcess.onExit();
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
            process.setExitCode((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
            process.onExit();
            continue;
         }

         int status = ret.getValue();
         if (WIFEXITED(status)) {
            status = WEXITSTATUS(status);
            if (status == 127) {
               process.setExitCode(Integer.MIN_VALUE);
            }
            else {
               process.setExitCode(status);
            }
         }
         else if (WIFSIGNALED(status)) {
            process.setExitCode(WTERMSIG(status));
         }
         else {
            process.setExitCode(Integer.MIN_VALUE);
         }

         process.onExit();
      }
   }
}
