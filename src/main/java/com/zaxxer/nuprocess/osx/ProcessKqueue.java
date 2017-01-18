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
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.internal.BaseEventProcessor;
import com.zaxxer.nuprocess.internal.LibC;
import com.zaxxer.nuprocess.osx.LibKevent.Kevent;
import com.zaxxer.nuprocess.osx.LibKevent.TimeSpec;

/**
 * @author Brett Wooldridge
 */
final class ProcessKqueue extends BaseEventProcessor<OsxProcess>
{
   private static final Logger LOGGER = Logger.getLogger(ProcessKqueue.class.getName());

   private static final int NUM_KEVENTS = 64;
   private static final int JAVA_PID;

   private volatile int kqueue;

   // Re-used in process() to avoid repeatedly allocating and destroying array of events.
   private final Kevent[] processEvents;
   private final BlockingQueue<OsxProcess> closeQueue;
   private final BlockingQueue<OsxProcess> registerProcess;
   private final BlockingQueue<OsxProcess> wantsStdin;
   private final BlockingQueue<OsxProcess> wantsStdout;
   private final BlockingQueue<OsxProcess> wantsStderr;

   static {
      JAVA_PID = LibC.getpid();
   }

   ProcessKqueue()
   {
      super(LINGER_ITERATIONS);
      kqueue = LibKevent.kqueue();
      if (kqueue < 0) {
         throw new RuntimeException("Unable to create kqueue");
      }

      closeQueue = new ArrayBlockingQueue<OsxProcess>(512);
      wantsStdin = new ArrayBlockingQueue<OsxProcess>(512);
      wantsStdout = new ArrayBlockingQueue<OsxProcess>(512);
      wantsStderr = new ArrayBlockingQueue<OsxProcess>(512);
      registerProcess = new ArrayBlockingQueue<OsxProcess>(512);
      processEvents = (Kevent[]) new Kevent().toArray(NUM_KEVENTS);
      

      // Listen for self-generated signal to tell processor to wake-up and handle pending "want" requests
      processEvents[0].EV_SET(LibC.SIGUSR2, Kevent.EVFILT_SIGNAL, Kevent.EV_ADD | Kevent.EV_RECEIPT, 0, 0l, Pointer.NULL);
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

      pidToProcessMap.put(process.getUid(), process);

      try {
         registerProcess.put(process);

         synchronized (process) {
            LibC.kill(JAVA_PID, LibC.SIGUSR2);
            process.wait();
         }
      }
      catch (InterruptedException e) {
         pidToProcessMap.remove(process.getUid());
         throw new RuntimeException("Interrupted during process registration");
      }
   }
   
   private void registerEvents(Kevent[] keventArray, int numEvents)
   {
      // When calling Structure.getPointer() to pass an argument to a native function,
      // you are responsible to call write() beforehand to copy the fields of the 
      // structure to native memory (and read() afterwards).
      for (int i = 0; i < numEvents; i++) {
         keventArray[i].write();
      }

      LibKevent.kevent(kqueue, keventArray[0].getPointer(), numEvents, null, 0, null);
      for (int i = 0; i < numEvents; i++) {
         keventArray[i].read();
      }
   }

   @Override
   public void queueRead(OsxProcess process, Stream stream)
   {
      if (shutdown) {
         return;
      }

      try {
         if (stream == NuProcess.Stream.STDOUT) {
            wantsStdout.put(process);
         }
         else {
            wantsStderr.put(process);
         }
         
         LibC.kill(JAVA_PID, LibC.SIGUSR2);
      }
      catch (InterruptedException e) {
         return;
      }
   }

   @Override
   public void queueWrite(OsxProcess process)
   {
      if (shutdown) {
         return;
      }

      try {
         wantsStdin.put(process);

         LibC.kill(JAVA_PID, LibC.SIGUSR2);
      }
      catch (InterruptedException e) {
         return;
      }
   }

   @Override
   public void queueCloseStdin(OsxProcess process)
   {
      if (shutdown) {
         return;
      }

      try {
         closeQueue.put(process);

         LibC.kill(JAVA_PID, LibC.SIGUSR2);
      }
      catch (InterruptedException e) {
         return;
      }
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
         return true;
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

   private void processEvent(Kevent kevent)
   {
      int ident = kevent.ident.intValue();
      int filter = (int) kevent.filter;
      long udata = Pointer.nativeValue(kevent.udata);

      if (filter == Kevent.EVFILT_SIGNAL) {
         checkStdinCloses();
         checkRegistrations();
         checkWants();
         return;
      }

      OsxProcess osxProcess = pidToProcessMap.get(udata);
      if (osxProcess == null) {
         throw new IllegalStateException("Event triggered for unknown process with identity " + ident);
      }

      if (filter == Kevent.EVFILT_READ) // stdout/stderr data available to read
      {
         processRead(kevent, ident, osxProcess);
      }
      else if (filter == Kevent.EVFILT_WRITE && ident == osxProcess.getStdin().get()) // Room in stdin pipe available to write
      {
         processWrite(kevent, osxProcess);
      }
      else if ((kevent.fflags & Kevent.NOTE_EXIT) != 0) { // process has exited
         processExit(kevent, osxProcess);
      }

      if (osxProcess.isProcessExited() &&
          ((!osxProcess.isWantStdout() || osxProcess.isStdoutClosed()) && (!osxProcess.isWantStderr() || osxProcess.isStderrClosed()))) {
         cleanupProcess(osxProcess);
         osxProcess.onExit();
      }
   }

   // ************************************************************************
   //                             Private methods
   // ************************************************************************

   private void processRead(final Kevent kevent, final int ident, final OsxProcess osxProcess)
   {
      final int available = kevent.data.intValue();
      boolean userWantsMore = false;
      if (ident == osxProcess.getStdout().get()) {
         userWantsMore = osxProcess.readStdout(available);
         if ((kevent.flags & Kevent.EV_EOF) != 0) {
            LOGGER.finest("process(): EV_EOF on stdout(" + ident + ") detected for " + osxProcess.getUid());
            userWantsMore = osxProcess.readStdout(-1);
         }
      }
      else if (ident == osxProcess.getStderr().get()) {
         userWantsMore = osxProcess.readStderr(available);
         if ((kevent.flags & Kevent.EV_EOF) != 0) {
            userWantsMore = osxProcess.readStderr(-1);
         }
      }

      if (userWantsMore) {
         LOGGER.finest("process(): action(EVFILT_READ) EV_ADD | EV_ONESHOT on stdout(" + ident + ") for " + osxProcess.getUid());
         processEvents[0].EV_SET(osxProcess.getStdout().get(), Kevent.EVFILT_READ, Kevent.EV_ADD | Kevent.EV_ONESHOT, 0, 0L, Pointer.createConstant(osxProcess.getUid()));
         registerEvents(processEvents, 1);               
      }
      else {
         LOGGER.finest("process(): process for " + osxProcess.getUid() + " requested no more data on stdout(" + ident + ")");
      }
   }

   private void processWrite(final Kevent kevent, final OsxProcess osxProcess)
   {
      final int available = kevent.data.intValue();
      boolean userWantsMore;
      if (available > 0) {
         userWantsMore = osxProcess.writeStdin(available);
      }
      else {
         userWantsMore = true;
      }

      final int stdinFd = osxProcess.getStdin().get();
      if (userWantsMore && stdinFd != -1) {
         processEvents[0].EV_SET(osxProcess.getStdin().get(), Kevent.EVFILT_WRITE, Kevent.EV_ADD | Kevent.EV_ONESHOT | Kevent.EV_RECEIPT, 0, 0l,
                                 Pointer.createConstant(osxProcess.getUid()));
         registerEvents(processEvents, 1);
      }
   }

   private void processExit(final Kevent kevent, final OsxProcess osxProcess)
   {
      int status = kevent.data.intValue();
      LOGGER.finest("Process " + osxProcess.getUid() + " exited with status " + status);
      osxProcess.markProcessExited();
      if (WIFEXITED(status)) {
         status = WEXITSTATUS(status);
         if (status == 127) {
            osxProcess.setExitCode(Integer.MIN_VALUE);
         }
         else {
            osxProcess.setExitCode(status);
         }
      }
      else if (WIFSIGNALED(status)) {
         osxProcess.setExitCode(WTERMSIG(status));
      }
      else {
         osxProcess.setExitCode(status);
      }
   }

   private void checkStdinCloses()
   {
      List<OsxProcess> processes = new ArrayList<OsxProcess>();
      // drainTo() is known to be atomic for ArrayBlockingQueue
      closeQueue.drainTo(processes);
      for (OsxProcess process : processes) {
         wantsStdin.remove(process);
         process.stdinClose();
      }
   }

   private void checkRegistrations()
   {
      if (!registerProcess.isEmpty()) {
         List<OsxProcess> processes = new ArrayList<OsxProcess>();
         // drainTo() is known to be atomic for ArrayBlockingQueue
         registerProcess.drainTo(processes, registerProcess.size());

         // We don't use the processEvents array here since we don't want to corrupt current events being processed
         Kevent[] events = (Kevent[]) new Kevent().toArray(1);

         for (OsxProcess process : processes) {
            // Listen for process exit (one-shot event)
            LOGGER.finest("action(EVFILT_PROC) EV_ADD | EV_ONESHOT | NOTE_EXIT | NOTE_EXITSTATUS for process with ident " + process.getUid());

            events[0].EV_SET((long) process.getPid(), Kevent.EVFILT_PROC, Kevent.EV_ADD | Kevent.EV_ONESHOT, Kevent.NOTE_EXIT | Kevent.NOTE_EXITSTATUS, 0l,
                             Pointer.createConstant(process.getUid()));
            
            try {
               registerEvents(events, 1);
            }
            finally {
               synchronized (process) {
                  process.notify();
               }
            }
         }
      }
   }

   private void checkWants()
   {
      if (!wantsStdin.isEmpty()) {
         List<OsxProcess> processes = new ArrayList<OsxProcess>();
         // drainTo() is known to be atomic for ArrayBlockingQueue
         wantsStdin.drainTo(processes, wantsStdin.size());
         // Enable stdin-ready notifications for each process that wants it.
         performWants(processes, STDIN);
      }

      if (!wantsStdout.isEmpty()) {
         List<OsxProcess> processes = new ArrayList<OsxProcess>();
         wantsStdout.drainTo(processes, wantsStdout.size());
         // Enable stdin-ready notifications for each process that wants it.
         performWants(processes, STDOUT);
      }

      if (!wantsStderr.isEmpty()) {
         List<OsxProcess> processes = new ArrayList<OsxProcess>();
         wantsStderr.drainTo(processes, wantsStderr.size());
         // Enable stdin-ready notifications for each process that wants it.
         performWants(processes, STDERR);
      }
   }

   private void performWants(final List<OsxProcess> processes, final int stream)
   {
      // We don't use the processEvents array here, since this is invoked from process()
      // and we would be overwriting the contents of that array while it's being processed.
      Kevent[] kevents = (Kevent[]) new Kevent().toArray(processes.size());
      int numKevents = 0;
      for (OsxProcess process : processes) {
         int fd = -1;
         int eventFilter = 0;

         switch (stream) {
         case STDIN:
            fd = process.getStdin().get();
            eventFilter = Kevent.EVFILT_WRITE;
            break;
         case STDOUT:
            fd = process.getStdout().get();
            eventFilter = Kevent.EVFILT_READ;
            break;
         case STDERR:
            fd = process.getStderr().get();
            eventFilter = Kevent.EVFILT_READ;
            break;
         }

         if (fd != -1) {
            kevents[numKevents].EV_SET(fd, eventFilter, Kevent.EV_ADD | Kevent.EV_ONESHOT | Kevent.EV_RECEIPT, 0, 0l,
                                       Pointer.createConstant(process.getUid()));
            numKevents++;
         }
      }

      registerEvents(kevents, numKevents);      
   }

   private void cleanupProcess(OsxProcess osxProcess)
   {
      LibC.waitpid(osxProcess.getPid(), new IntByReference(), LibC.WNOHANG);

      // If this is the last process in the map, this thread will cleanly shut down.
      pidToProcessMap.remove(osxProcess.getUid());
      if (!wantsStdin.isEmpty()) {
         while (wantsStdin.remove(osxProcess)) {
            continue;
         }
      }
   }
}
