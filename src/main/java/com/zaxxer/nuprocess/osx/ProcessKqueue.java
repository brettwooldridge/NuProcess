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
import com.zaxxer.nuprocess.osx.LibKevent.TimeSpec;

import static com.zaxxer.nuprocess.internal.LibC.*;

/**
 * @author Brett Wooldridge
 */
final class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
   private static final int NUM_KEVENTS = 64;
   private static final TimeSpec timeSpec;
   private static final int JAVA_PID;

   private volatile int kqueue;
   private ThreadLocal<KeventArray> keventArrays;
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

      keventArrays = new ThreadLocal<KeventArray>() {
         @Override
         protected KeventArray initialValue() {
            return new KeventArray(NUM_KEVENTS);
         }
        };

      KeventArray keventArray = keventArrays.get();

      // Listen for self-generated signal to tell processor to wake-up and handling pending stdin requests
      Kevent.EV_SET(
          keventArray.get(0),
          LibC.SIGUSR2, Kevent.EVFILT_SIGNAL, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, null);
      registerEvents(keventArray, 1);
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

      KeventArray keventArray = keventArrays.get();

      // Listen for process exit (one-shot event)
      Kevent.EV_SET(
          keventArray.get(0),
          (long)process.getPid(),
          Kevent.EVFILT_PROC,
          Kevent.EV_ADD | Kevent.EV_RECEIPT | Kevent.EV_ONESHOT, Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS | Kevent.NOTE_REAP,
          0l,
          pidPointer);

      // Listen for stdout and stderr data availability (events deleted automatically when file descriptors closed)
      Kevent.EV_SET(
          keventArray.get(1),
          process.getStdout().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, pidPointer);
      Kevent.EV_SET(
          keventArray.get(2),
          process.getStderr().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, pidPointer);

      // Listen for stdin data availability (initially disabled until user wants read, deleted automatically when file descriptor closed)
      Kevent.EV_SET(
          keventArray.get(3),
          process.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_DISABLE | Kevent.EV_RECEIPT, 0, 0l, pidPointer);

      registerEvents(keventArray, 4);
   }

   private void registerEvents(KeventArray keventArray, int numEvents)
   {
      int ret = LibKevent.kevent(kqueue, keventArray.getPointer(), numEvents, keventArray.getPointer(), numEvents, null);
      if (ret != numEvents) {
        throw new RuntimeException(String.format("Error %d registering events (ret=%d)", Native.getLastError(), ret));
      } else {
        // On success, each returned event will have 0 in the 'data' field.
        // On failure, at least one returned event will have a non-0 value in the 'data' field.
        StringBuilder err = new StringBuilder();
        for (int i = 0; i < ret; i++) {
          int error = (int) keventArray.get(i).getData();
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
      KeventArray keventArray = keventArrays.get();
      int nev = LibKevent.kevent(kqueue, null, 0, keventArray.getPointer(), NUM_KEVENTS, timeSpec);
      if (nev == -1) {
         throw new RuntimeException("Error waiting for kevent");
      }

      if (nev == 0) {
         return false;
      }

      for (int i = 0; i < nev; i++) {
        processEvent(keventArray.get(i));
      }
      return true;
   }

   private void processEvent(Kevent kevent)
   {
      int ident = (int) kevent.getIdent();
      int filter = kevent.getFilter();
      int udata = (int) Pointer.nativeValue(kevent.getUserData());

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
         int available = (int) kevent.getData();
         if (ident == osxProcess.getStdout().get()) {
            osxProcess.readStdout(available);
            if ((kevent.getFlags() & Kevent.EV_EOF) != 0) {
               osxProcess.readStdout(-1);
            }
         }
         else if (ident == osxProcess.getStderr().get()) {
            osxProcess.readStderr(available);
            if ((kevent.getFlags() & Kevent.EV_EOF) != 0) {
               osxProcess.readStderr(-1);
            }
         }
      }
      else if (filter == Kevent.EVFILT_WRITE && ident == osxProcess.getStdin().get()) // Room in stdin pipe available to write
      {
         int available = (int) kevent.getData();
         boolean userWantsMore;
         if (available > 0) {
            userWantsMore = osxProcess.writeStdin(available);
         } else {
            userWantsMore = true;
         }
         if (!userWantsMore) {
            // No more stdin for now. Disable the event.
            KeventArray keventArray = keventArrays.get();
            Kevent disableEvent = keventArray.get(0);
            int pid = osxProcess.getPid();
            Pointer pidPointer = new Pointer(pid);
            Kevent.EV_SET(
               disableEvent,
               osxProcess.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_DISABLE | Kevent.EV_RECEIPT, 0, 0l, pidPointer);
            registerEvents(keventArray, 1);
         }
      }
      else if ((kevent.getFilterFlags() & Kevent.NOTE_EXIT) != 0) // process has exited System.gc()
      {
         cleanupProcess(osxProcess);
         int status = (int) kevent.getData();
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
     closeQueue.drainTo(processes);
     for (OsxProcess process : processes) {
        process.stdinClose();
     }
   }

   private void checkWaitWrites()
   {
      List<OsxProcess> processes = new ArrayList<OsxProcess>();
      wantsWrite.drainTo(processes, NUM_KEVENTS);
      if (!processes.isEmpty()) {
         // Enable stdin-ready notifications for each process which wants it.
         KeventArray keventArray = keventArrays.get();
         for (int i = 0; i < processes.size(); i++) {
            OsxProcess process = processes.get(i);
            Kevent kevent = keventArray.get(i);
            int pid = process.getPid();
            Pointer pidPointer = new Pointer(pid);
            Kevent.EV_SET(
                kevent,
                process.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ENABLE | Kevent.EV_RECEIPT, 0, 0l, pidPointer);
         }
         registerEvents(keventArray, processes.size());
      }
   }

   private void cleanupProcess(OsxProcess osxProcess)
   {
      LibC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);

      pidToProcessMap.remove(osxProcess.getPid());
   }
}