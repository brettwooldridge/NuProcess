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

package com.zaxxer.nuprocess.osx;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.osx.LibKevent.TimeSpec;

/**
 * @author Brett Wooldridge
 */
final class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
   private static final int KEVENT_POOL_SIZE = 16;
   private static final TimeSpec timeSpec;
   private static final int JAVA_PID;

   private volatile int kqueue;
   private Kevent triggeredEvent;
   private BlockingQueue<Kevent> keventPool;
   private BlockingQueue<OsxProcess> closeQueue;
   private BlockingQueue<OsxProcess> wantsWrite;

   static {
      JAVA_PID = LibC.getpid();

      timeSpec = new TimeSpec();
      timeSpec.tv_sec = 0;
      timeSpec.tv_nsec = TimeUnit.MILLISECONDS.toNanos(DEADPOOL_POLL_INTERVAL);
   }

   ProcessKqueue() {
      kqueue = LibKevent.kqueue();
      if (kqueue < 0) {
         throw new RuntimeException("Unable to create kqueue");
      }

      closeQueue = new ArrayBlockingQueue<OsxProcess>(512);
      wantsWrite = new ArrayBlockingQueue<OsxProcess>(512);

      triggeredEvent = new Kevent();

      keventPool = new ArrayBlockingQueue<Kevent>(KEVENT_POOL_SIZE);
      for (int i = 0; i < KEVENT_POOL_SIZE; i++) {
         keventPool.add(new Kevent());
      }
   }

   // ************************************************************************
   //                         IEventProcessor methods
   // ************************************************************************

   @Override
   public void registerProcess(OsxProcess process)
   {
      if (shutdown) {
         return;
      }

      int pid = process.getPid();
      Pointer pidPointer = new Pointer(pid);

      pidToProcessMap.put(pid, process);

      // Listen for process exit
      queueEvent(process.getPid(), Kevent.EVFILT_PROC, Kevent.EV_ADD | Kevent.EV_ONESHOT, Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
                 pidPointer);
      // Listen for self-generated signal to tell processor to wake-up and handling pending stdin requests
      queueEvent(LibC.SIGUSR2, Kevent.EVFILT_SIGNAL, Kevent.EV_ADD, 0, pidPointer);

      // Listen for stdout and stderr data availability
      queueEvent(process.getStdout().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, pidPointer);
      queueEvent(process.getStderr().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, pidPointer);
   }

   @Override
   public void queueWrite(OsxProcess process)
   {
      if (shutdown) {
         return;
      }

      try {
         logger.debug("queued write: {}", process.getPid());
         wantsWrite.put(process);
      }
      catch (InterruptedException e) {
         return;
      }

      LibC.kill(JAVA_PID, LibC.SIGUSR2);
   }

   @Override
   public void closeStdin(OsxProcess process)
   {
      logger.debug("queued stdin close: {}", process.getPid());
      closeQueue.add(process);
   }

   @Override
   public boolean process()
   {
      int nev = LibKevent.kevent(kqueue, null, 0, triggeredEvent.getPointer(), 1, timeSpec);
      if (nev == -1) {
         throw new RuntimeException("Error waiting for kevent");
      }

      if (nev == 0) {
         return false;
      }

      final Kevent kevent = triggeredEvent;
      int ident = (int) kevent.getIdent();
      int filter = kevent.getFilter();
      int udata = (int) Pointer.nativeValue(kevent.getUserData());

      OsxProcess osxProcess = pidToProcessMap.get(udata);
      if (osxProcess == null) {
         osxProcess = pidToProcessMap.get(ident);
         if (osxProcess == null) {
            return true;
         }
      }

      logger.debug("Processing event: {}", Kevent.getEventName(filter));

      if (filter == Kevent.EVFILT_READ) // stdout/stderr data available to read
      {
         int available = (int) kevent.getData();
         if (ident == osxProcess.getStdout().get()) {
            osxProcess.readStdout(available);
            if ((kevent.getFlags() & Kevent.EV_EOF) != 0) {
               osxProcess.readStdout(-1);
            }
            else {
               queueEvent(ident, Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, kevent.getUserData());
            }
         }
         else if (ident == osxProcess.getStderr().get()) {
            osxProcess.readStderr(available);
            if ((kevent.getFlags() & Kevent.EV_EOF) != 0) {
               osxProcess.readStderr(-1);
            }
            else {
               queueEvent(osxProcess.getStderr().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, kevent.getUserData());
            }
         }
      }
      else if (filter == Kevent.EVFILT_WRITE && ident == osxProcess.getStdin().get()) // Room in stdin pipe available to write
      {
         int available = (int) kevent.getData();
         if (available == 0 || osxProcess.writeStdin(4096)) {
            queueEvent(osxProcess.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, new Pointer(osxProcess.getPid()));
         }
      }
      else if ((kevent.getFilterFlags() & Kevent.NOTE_EXIT) != 0) // process has exited System.gc()
      {
         cleanupProcess(osxProcess);
         int rc = ((int) kevent.getData() & 0xff00) >> 8;
         osxProcess.onExit(rc);
      }
      else if (filter == Kevent.EVFILT_SIGNAL) {
         checkStdinCloses();
         checkWaitWrites();
      }

      kevent.clear();

      return true;
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   private void queueEvent(int handle, int filter, int flags, int fflags, Pointer udata)
   {
      try {
         Kevent kevent = keventPool.take();

         Kevent.EV_SET(kevent, (long) handle, filter, flags, fflags, 0l, udata);
         LibKevent.kevent(kqueue, kevent.getPointer(), 1, null, 0, null);

         keventPool.put(kevent);
      }
      catch (InterruptedException e) {
         return;
      }
   }

   private void checkStdinCloses()
   {
      if (closeQueue.isEmpty()) {
         return;
      }

      OsxProcess process;
      while ((process = closeQueue.poll()) != null) {
         process.stdinClose();
      }
   }

   private void checkWaitWrites()
   {
      if (wantsWrite.isEmpty()) {
         return;
      }

      OsxProcess process;
      while ((process = wantsWrite.poll()) != null) {
         int fd = process.getStdin().get();
         if (fd != -1) {
            queueEvent(process.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, new Pointer(process.getPid()));
         }
      }
   }

   private void cleanupProcess(OsxProcess osxProcess)
   {
      LibC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);

      pidToProcessMap.remove(osxProcess.getPid());

      osxProcess.stdinClose();
      logger.debug("cleanup closed stdin: {}", osxProcess.getPid());
   }
}