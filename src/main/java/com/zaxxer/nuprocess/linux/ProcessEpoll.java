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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.internal.LinuxLibC;

import java.nio.ByteBuffer;

import static com.zaxxer.nuprocess.internal.LibC.WIFEXITED;
import static com.zaxxer.nuprocess.internal.LibC.WEXITSTATUS;
import static com.zaxxer.nuprocess.internal.LibC.WIFSIGNALED;
import static com.zaxxer.nuprocess.internal.LibC.WTERMSIG;

/**
 * @author Brett Wooldridge
 */
class ProcessEpoll extends BaseEventProcessor<LinuxProcess>
{
   private static final int EVENT_POOL_SIZE = 32;

   private int epoll;
   private int signalFd;
   private EpollEvent triggeredEvent;

   private static BlockingQueue<EpollEvent> eventPool;

   ProcessEpoll()
   {
      epoll = LibEpoll.epoll_create(1024);
      if (epoll < 0) {
         throw new RuntimeException("Unable to create epoll: " + Native.getLastError());
      }
      NativeLongByReference sigset = new NativeLongByReference();
      if (LibC.sigemptyset(sigset) != 0 || LibC.sigaddset(sigset, LinuxLibC.SIGCHLD) != 0) {
         throw new RuntimeException("Unable to initialize sigset");
      }
      signalFd = LinuxLibC.signalfd(-1, sigset.getValue(), LinuxLibC.SFD_CLOEXEC);
      if (signalFd == -1) {
         throw new RuntimeException("signalfd failed");
      }
      EpollEvent event = new EpollEvent();
      event.events = LibEpoll.EPOLLIN;
      event.data.fd = signalFd;
      int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, signalFd, event);
      if (rc == -1) {
	 rc = Native.getLastError();
	 throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
      }
      triggeredEvent = new EpollEvent();
      eventPool = new ArrayBlockingQueue<EpollEvent>(EVENT_POOL_SIZE);
      for (int i = 0; i < EVENT_POOL_SIZE; i++) {
         eventPool.add(new EpollEvent());
      }
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

      int stdoutFd = process.getStdout().get();
      int stderrFd = process.getStderr().get();

      pidToProcessMap.put(process.getPid(), process);
      fildesToProcessMap.put(process.getStdin().get(), process);
      fildesToProcessMap.put(stdoutFd, process);
      fildesToProcessMap.put(stderrFd, process);

      try {
         EpollEvent event = eventPool.take();
         event.events = LibEpoll.EPOLLIN;
         event.data.fd = stdoutFd;
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdoutFd, event);
         if (rc == -1) {
            rc = Native.getLastError();
            eventPool.put(event);
            throw new RuntimeException("Unable to register new events to epoll, errorcode: " + rc);
         }
         eventPool.put(event);

         event = eventPool.take();
         event.events = LibEpoll.EPOLLIN;
         event.data.fd = stderrFd;
         rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stderrFd, event);
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

   @Override
   public void queueWrite(LinuxProcess process)
   {
      if (shutdown) {
         return;
      }

      try {
         int stdin = process.getStdin().get();
         if (stdin == -1) {
           return;
         }
         EpollEvent event = eventPool.take();
         event.events = LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP;
         event.data.fd = stdin;
         int rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, stdin, event);
         if (rc == -1) {
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, event);
            rc = LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_ADD, stdin, event);
         }

         eventPool.put(event);
         if (rc == -1) {
            throw new RuntimeException("Unable to register new event to epoll queue");
         }
      }
      catch (InterruptedException ie) {
         throw new RuntimeException(ie);
      }
   }

   @Override
   public void closeStdin(LinuxProcess process)
   {
      int stdin = process.getStdin().get();
      if (stdin != -1) {
         fildesToProcessMap.remove(stdin);
         LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, stdin, null);
      }
   }

   @Override
   public boolean process()
   {
      try {
         int nev = LibEpoll.epoll_wait(epoll, triggeredEvent, 1, -1);
         if (nev == -1) {
            throw new RuntimeException("Error waiting for epoll");
         }

         if (nev == 0) {
            return false;
         }

         EpollEvent epEvent = triggeredEvent;
         int ident = epEvent.data.fd;
         int events = epEvent.events;

	 if (ident == signalFd) {
	     handleSigchld(epEvent);
	     return true;
	 }

         LinuxProcess linuxProcess = fildesToProcessMap.get(ident);
         if (linuxProcess == null) {
            return true;
         }

         if ((events & LibEpoll.EPOLLIN) != 0) // stdout/stderr data available to read
         {
            if (ident == linuxProcess.getStdout().get()) {
               linuxProcess.readStdout(NuProcess.BUFFER_CAPACITY);
            }
            else {
               linuxProcess.readStderr(NuProcess.BUFFER_CAPACITY);
            }
         }
         else if ((events & LibEpoll.EPOLLOUT) != 0) // Room in stdin pipe available to write
         {
            if (linuxProcess.getStdin().get() != -1) {
               if (linuxProcess.writeStdin(NuProcess.BUFFER_CAPACITY)) {
                  epEvent.events = LibEpoll.EPOLLOUT | LibEpoll.EPOLLONESHOT | LibEpoll.EPOLLRDHUP | LibEpoll.EPOLLHUP;
                  LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_MOD, ident, epEvent);
               }
            }
         }

         if ((events & LibEpoll.EPOLLHUP) != 0 || (events & LibEpoll.EPOLLRDHUP) != 0 || (events & LibEpoll.EPOLLERR) != 0) {
            LibEpoll.epoll_ctl(epoll, LibEpoll.EPOLL_CTL_DEL, ident, null);
            if (ident == linuxProcess.getStdout().get()) {
               linuxProcess.readStdout(-1);
            }
            else if (ident == linuxProcess.getStderr().get()) {
               linuxProcess.readStderr(-1);
            }
            else if (ident == linuxProcess.getStdin().get()) {
               linuxProcess.closeStdin(true);
            }
         }

         return true;
      }
      finally {
         triggeredEvent.clear();
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   private void handleSigchld(EpollEvent event)
   {
      LinuxLibC.SignalFdSiginfo siginfo = new LinuxLibC.SignalFdSiginfo();
      ByteBuffer buf = siginfo.getPointer().getByteBuffer(0, 0);
      int bytesRead = LibC.read(signalFd, buf, siginfo.size());
      if (bytesRead != siginfo.size()) {
	 throw new RuntimeException("Couldn't read struct signalfd_siginfo");
      }
      siginfo.read();
      int pid = siginfo.ssi_pid;
      LinuxProcess linuxProcess = pidToProcessMap.get(pid);
      if (linuxProcess == null) {
	 return;
      }
      IntByReference ret = new IntByReference();
      int rc = LibC.waitpid(linuxProcess.getPid(), ret, LibC.WNOHANG);

      if (rc == 0) {
	 return;
      }
      else if (rc < 0) {
         linuxProcess.onExit((Native.getLastError() == LibC.ECHILD) ? Integer.MAX_VALUE : Integer.MIN_VALUE);
      }
      else {
         int status = ret.getValue();
         if (WIFEXITED(status)) {
            status = WEXITSTATUS(status);
            if (status == 127) {
               linuxProcess.onExit(Integer.MIN_VALUE);
            }
            else {
               linuxProcess.onExit(status);
            }
         }
         else if (WIFSIGNALED(status)) {
            linuxProcess.onExit(WTERMSIG(status));
         }
         else {
            linuxProcess.onExit(Integer.MIN_VALUE);
         }
      }
   }
}
