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

import static com.zaxxer.nuprocess.internal.LibC.WEXITSTATUS;
import static com.zaxxer.nuprocess.internal.LibC.WIFEXITED;
import static com.zaxxer.nuprocess.internal.LibC.WIFSIGNALED;
import static com.zaxxer.nuprocess.internal.LibC.WTERMSIG;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.osx.LibKevent.Kevent;
import com.zaxxer.nuprocess.osx.LibKevent.TimeSpec;

/**
 * @author Brett Wooldridge
 */
final class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
   // If we return false from process() even once while no processes
   // are registered, we'll shut down the event processor thread.
   private static final int LINGER_ITERATIONS = 0;
   private static final int NUM_KEVENTS = 64;
   private static final int JAVA_PID;

   private volatile int kqueue;

   // Re-used in process() to avoid repeatedly allocating and destroying array of events.
   private Kevent[] processEvents;
   private BlockingQueue<OsxProcess> closeQueue;
   private BlockingQueue<OsxProcess> wantsWrite;

   static {
      JAVA_PID = LibC.getpid();
   }

   ProcessKqueue()
   {
      this(LINGER_ITERATIONS);
   }

   ProcessKqueue(OsxProcess process)
   {
      this(-1);

      registerProcess(process);
      checkAndSetRunning();
   }

   private ProcessKqueue(int lingerIterations)
   {
      super(lingerIterations);

      kqueue = LibKevent.kqueue();
      if (kqueue < 0) {
         throw new RuntimeException("Unable to create kqueue");
      }

      closeQueue = new ArrayBlockingQueue<>(512);
      wantsWrite = new ArrayBlockingQueue<>(512);
      processEvents = (Kevent[]) new Kevent().toArray(NUM_KEVENTS);

      // Listen for self-generated signal to tell processor to wake-up and handling pending stdin requests
      processEvents[0].EV_SET(LibC.SIGUSR2, Kevent.EVFILT_SIGNAL, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0L, Pointer.NULL);
      registerEvents(processEvents, 1);
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
      Pointer pidPointer = Pointer.createConstant(pid);

      pidToProcessMap.put(pid, process);

      Integer stdinFd = null;
      Integer stdoutFd = null;
      Integer stderrFd = null;
      try {
         stdinFd = process.getStdin().acquire();
         stdoutFd = process.getStdout().acquire();
         stderrFd = process.getStderr().acquire();
         // We don't use the processEvents array here, since this method is not
         // called on the event processor thread.
         Kevent[] events = (Kevent[]) new Kevent().toArray(4);
         // Listen for process exit (one-shot event)
         events[0].EV_SET((long) pid, Kevent.EVFILT_PROC, Kevent.EV_ADD | Kevent.EV_RECEIPT | Kevent.EV_ONESHOT,
                          Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP, 0L, pidPointer);
         // Listen for stdout and stderr data availability (events deleted automatically when file descriptors closed)
         events[1].EV_SET(stdoutFd, Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0L, pidPointer);
         events[2].EV_SET(stderrFd, Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0L, pidPointer);
         // Listen for stdin data availability (initially disabled until user wants read, deleted automatically when file descriptor closed)
         events[3].EV_SET(stdinFd, Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_DISABLE | Kevent.EV_RECEIPT, 0, 0L, pidPointer);

         registerEvents(events, 4);
      } finally {
         if (stdinFd != null) {
            process.getStdin().release();
         }
         if (stdoutFd != null) {
            process.getStdout().release();
         }
         if (stderrFd != null) {
            process.getStderr().release();
         }
      }
   }

   private void registerEvents(Kevent[] keventArray, int numEvents)
   {
      // When calling Structure.getPointer() to pass an argument to a
      // native function, you are responsible to call write() beforehand
      // to copy the fields of the structure to native memory (and read() afterwards).
      for (int i = 0; i < numEvents; i++) {
         keventArray[i].write();
      }
      int ret = LibKevent.kevent(kqueue, keventArray[0].getPointer(), numEvents, keventArray[0].getPointer(), numEvents, null);
      for (int i = 0; i < numEvents; i++) {
         keventArray[i].read();
      }
      if (ret != numEvents) {
         throw new RuntimeException(String.format("Error %d registering events (ret=%d)", Native.getLastError(), ret));
      }
      else {
         // On success, each returned event will have 0 in the 'data' field.
         // On failure, at least one returned event will have a non-0 value in the 'data' field.
         StringBuilder err = new StringBuilder();
         for (int i = 0; i < ret; i++) {
            int error = keventArray[i].data.intValue();
            if (error != 0) {
               err.append(String.format("kevent %d: error %d\n", i, error));
            }
         }
         if (err.length() > 0) {
            throw new RuntimeException(err.toString());
         }
      }
   }

   @Override
   public void queueWrite(OsxProcess process)
   {
      if (shutdown) {
         return;
      }

      try {
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
      try {
         closeQueue.put(process);
      }
      catch (InterruptedException e) {
         // ignore
      }

      LibC.kill(JAVA_PID, LibC.SIGUSR2);
   }

   @Override
   public boolean process()
   {
      TimeSpec timeout;
      if (pidToProcessMap.isEmpty()) {
         // No processes registered. Wait up to LINGER_TIME_MS for a process to show up
         // and signal one of its registered events.
         //
         // We know at least one event is registered on the kqueue (EVFILT_SIGNAL),
         // so kevent() will block. (If no events are registered, kevent() will not block
         // even if you pass it a timeout, unlike select()).
         timeout = new TimeSpec();
         // kevent() will return EINVAL if tv_nsec represents a value > 1 second,
         // so split the linger time over tv_sec and tv_nsec.
         timeout.tv_sec = LINGER_TIME_MS / 1000;
         timeout.tv_nsec = TimeUnit.MILLISECONDS.toNanos(LINGER_TIME_MS % 1000);
      }
      else {
         // At least one process is registered. There's no need to have a timeout.
         timeout = null;
      }

      int nev = LibKevent.kevent(kqueue, null, 0, processEvents[0].getPointer(), processEvents.length, timeout);
      if (nev == -1) {
         throw new RuntimeException("Error waiting for kevent");
      }

      if (nev == 0) {
         return false;
      }

      // When calling Structure.getPointer() to pass an argument to a
      // native function, you are responsible to call read() afterwards to
      // populate the fields of the structure(s).
      for (int i = 0; i < nev; i++) {
         processEvents[i].read();
         processEvent(processEvents[i]);
      }
      return true;
   }

   /**
    * Closes the {@code kqueue} file descriptor.
    *
    * @since 1.3
    */
   @Override
   protected void close()
   {
      LibC.close(kqueue);
   }

   private void processEvent(Kevent kevent)
   {
      int ident = kevent.ident.intValue();
      int filter = (int) kevent.filter;
      int udata = (int) (Pointer.nativeValue(kevent.udata));

      if (filter == Kevent.EVFILT_SIGNAL) {
         checkStdinCloses();
         checkWaitWrites();
         return;
      }

      OsxProcess osxProcess = pidToProcessMap.get(udata);
      if (osxProcess == null) {
         osxProcess = pidToProcessMap.get(ident);
         if (osxProcess == null) {
            return;
         }
      }

      if (filter == Kevent.EVFILT_READ) // stdout/stderr data available to read
      {
         int available = kevent.data.intValue();
         try {
            int stdoutFd = osxProcess.getStdout().acquire();
            if (ident == stdoutFd) {
               osxProcess.readStdout(available, stdoutFd);
               if ((kevent.flags & Kevent.EV_EOF) != 0) {
                  osxProcess.readStdout(-1, stdoutFd);
               }
               return;
            }
         } finally {
            osxProcess.getStdout().release();
         }

         try {
            int stderrFd = osxProcess.getStderr().acquire();
            if (ident == stderrFd) {
               osxProcess.readStderr(available, stderrFd);
               if ((kevent.flags & Kevent.EV_EOF) != 0) {
                  osxProcess.readStderr(-1, stderrFd);
               }
            }
         } finally {
            osxProcess.getStderr().release();
         }
      }
      else if (filter == Kevent.EVFILT_WRITE) // Room in stdin pipe available to write
      {
         try {
            int stdinFd = osxProcess.getStdin().acquire();
            if (ident == stdinFd) {
               int available = kevent.data.intValue();
               boolean userWantsMore;
               if (available > 0) {
                  userWantsMore = osxProcess.writeStdin(available, stdinFd);
               }
               else {
                  userWantsMore = true;
               }
               if (!userWantsMore) {
                  // No more stdin for now. Disable the event.
                  // We could use processEvents here and overwrite just the first entry, but this probably doesn't happen
                  // enough to warrant that optimization.
                  Kevent[] events = (Kevent[]) new Kevent().toArray(1);
                  events[0].EV_SET(stdinFd, Kevent.EVFILT_WRITE, Kevent.EV_DISABLE | Kevent.EV_RECEIPT, 0, 0L,
                                   Pointer.createConstant(osxProcess.getPid()));
                  registerEvents(events, 1);
               }
            }
         } finally {
            osxProcess.getStdin().release();
         }
      }
      else if ((kevent.fflags & Kevent.NOTE_EXIT) != 0) // process has exited System.gc()
      {
         cleanupProcess(osxProcess);
         int status = kevent.data.intValue();
         if (WIFEXITED(status)) {
            status = WEXITSTATUS(status);
            if (status == 127) {
               osxProcess.onExit(Integer.MIN_VALUE);
            }
            else {
               osxProcess.onExit(status);
            }
         }
         else if (WIFSIGNALED(status)) {
            osxProcess.onExit(WTERMSIG(status));
         }
         else {
            osxProcess.onExit(status);
         }
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   private void checkStdinCloses()
   {
      List<OsxProcess> processes = new ArrayList<>();
      // drainTo() is known to be atomic for ArrayBlockingQueue
      closeQueue.drainTo(processes);
      for (OsxProcess process : processes) {
         process.getStdin().close();
      }
   }

   @SuppressWarnings("ForLoopReplaceableByForEach")
   private void checkWaitWrites()
   {
      List<OsxProcess> processes = new ArrayList<>();
      // drainTo() is known to be atomic for ArrayBlockingQueue
      wantsWrite.drainTo(processes, NUM_KEVENTS);
      if (!processes.isEmpty()) {
         // Enable stdin-ready notifications for each process which wants it.
         // We don't use the processEvents array here, since this is invoked from process()
         // and we would be overwriting the contents of that array while it's being processed.
         Kevent[] kevents = (Kevent[]) new Kevent().toArray(processes.size());
         int numKevents = 0;
         for (int i = 0; i < processes.size(); i++) {
            OsxProcess process = processes.get(i);
            try {
               int fd = process.getStdin().acquire();
               if (fd != -1) {
                  kevents[numKevents].EV_SET(fd, Kevent.EVFILT_WRITE, Kevent.EV_ENABLE | Kevent.EV_RECEIPT, 0, 0L,
                                             Pointer.createConstant(process.getPid()));
                  numKevents++;
               }
            } finally {
               process.getStdin().release();
            }
         }
         registerEvents(kevents, numKevents);
      }
   }

   private void cleanupProcess(OsxProcess osxProcess)
   {
      LibC.waitpid(osxProcess.getPid(), exitCodePointer, LibC.WNOHANG);

      // If this is the last process in the map, this thread will cleanly shut down.
      pidToProcessMap.remove(osxProcess.getPid());
      Iterator<OsxProcess> iterator = wantsWrite.iterator();
      while (iterator.hasNext()) {
         if (osxProcess == iterator.next()) {
            iterator.remove();
         }
      }
   }
}
