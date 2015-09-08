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
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.osx.LibKevent.Kevent;
import com.zaxxer.nuprocess.osx.LibKevent.TimeSpec;

import static com.zaxxer.nuprocess.internal.LibC.*;

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
   private ThreadLocal<Kevent[]> keventArray;
   private BlockingQueue<OsxProcess> closeQueue;
   private BlockingQueue<OsxProcess> wantsWrite;

   static {
      JAVA_PID = LibC.getpid();
   }

   ProcessKqueue() {
      super(LINGER_ITERATIONS);
      kqueue = LibKevent.kqueue();
      if (kqueue < 0) {
         throw new RuntimeException("Unable to create kqueue");
      }

      closeQueue = new ArrayBlockingQueue<OsxProcess>(512);
      wantsWrite = new ArrayBlockingQueue<OsxProcess>(512);
      keventArray = new ThreadLocal<Kevent[]>() {
          @Override
          protected Kevent[] initialValue() {
              return (Kevent[]) new Kevent().toArray(NUM_KEVENTS);
          }
      };

      // Listen for self-generated signal to tell processor to wake-up and handling pending stdin requests
      Kevent[] events = keventArray.get();
      events[0].EV_SET(LibC.SIGUSR2, Kevent.EVFILT_SIGNAL, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, Pointer.NULL);
      registerEvents(events, 1);
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

      // We don't use keventArray.get() here since this is called from the user's thread, so allocating
      // the thread-local events array which will never be re-used is pretty wasteful.
      Kevent[] events = (Kevent[]) new Kevent().toArray(4);
      // Listen for process exit (one-shot event)
      events[0].EV_SET(
          (long) pid,
          Kevent.EVFILT_PROC,
          Kevent.EV_ADD | Kevent.EV_RECEIPT | Kevent.EV_ONESHOT, Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
          0l,
          pidPointer);
      // Listen for stdout and stderr data availability (events deleted automatically when file descriptors closed)
      events[1].EV_SET(
          process.getStdout().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, pidPointer);
      events[2].EV_SET(
          process.getStderr().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, pidPointer);
      // Listen for stdin data availability (initially disabled until user wants read, deleted automatically when file descriptor closed)
      events[3].EV_SET(
          process.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_DISABLE | Kevent.EV_RECEIPT, 0, 0l, pidPointer);

      registerEvents(events, 4);
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
      } else {
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
      closeQueue.add(process);
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
      } else {
         // At least one process is registered. There's no need to have a timeout.
         timeout = null;
      }

      Kevent[] kevents = keventArray.get();
      int nev = LibKevent.kevent(kqueue, null, 0, kevents[0].getPointer(), NUM_KEVENTS, timeout);
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
        kevents[i].read();
        processEvent(kevents[i]);
      }
      return true;
   }

   @Override
   protected void cleanup()
   {
      keventArray.remove();
   }

   private void processEvent(Kevent kevent)
   {
      int ident = kevent.ident.intValue();
      int filter = (int) kevent.filter;
      int udata = (int) (0xFFFFFFFF & Pointer.nativeValue(kevent.udata));

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
         if (ident == osxProcess.getStdout().get()) {
            osxProcess.readStdout(available);
            if ((kevent.flags & Kevent.EV_EOF) != 0) {
               osxProcess.readStdout(-1);
            }
         }
         else if (ident == osxProcess.getStderr().get()) {
            osxProcess.readStderr(available);
            if ((kevent.flags & Kevent.EV_EOF) != 0) {
               osxProcess.readStderr(-1);
            }
         }
      }
      else if (filter == Kevent.EVFILT_WRITE && ident == osxProcess.getStdin().get()) // Room in stdin pipe available to write
      {
         int available = kevent.data.intValue();
         boolean userWantsMore;
         if (available > 0) {
            userWantsMore = osxProcess.writeStdin(available);
         } else {
            userWantsMore = true;
         }
         if (!userWantsMore) {
            // No more stdin for now. Disable the event.
            Kevent[] events = keventArray.get();
            events[0].EV_SET(
                osxProcess.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_DISABLE | Kevent.EV_RECEIPT, 0, 0l, Pointer.createConstant(osxProcess.getPid()));
            registerEvents(events, 1);
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
     List<OsxProcess> processes = new ArrayList<OsxProcess>();
     // drainTo() is known to be atomic for ArrayBlockingQueue
     closeQueue.drainTo(processes);
     for (OsxProcess process : processes) {
        process.stdinClose();
     }
   }

   private void checkWaitWrites()
   {
      List<OsxProcess> processes = new ArrayList<OsxProcess>();
     // drainTo() is known to be atomic for ArrayBlockingQueue
      wantsWrite.drainTo(processes, NUM_KEVENTS);
      if (!processes.isEmpty()) {
         // Enable stdin-ready notifications for each process which wants it.
         Kevent[] kevents = keventArray.get();
         for (int i = 0; i < processes.size(); i++) {
            OsxProcess process = processes.get(i);
            kevents[i].EV_SET(
                process.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ENABLE | Kevent.EV_RECEIPT, 0, 0l, Pointer.createConstant(process.getPid()));
         }
         registerEvents(kevents, processes.size());
      }
   }

   private void cleanupProcess(OsxProcess osxProcess)
   {
      LibC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);

      // If this is the last process in the map, this thread will cleanly shut down.
      pidToProcessMap.remove(osxProcess.getPid());
   }
}
