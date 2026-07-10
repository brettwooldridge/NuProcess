/*
 * Copyright (C) 2026 Brett Wooldridge
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

package com.zaxxer.nuprocess.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Shared plumbing for the Java FFM (Foreign Function &amp; Memory) bindings used by
 * NuProcess: the native linker, symbol lookup, per-thread capture of
 * {@code errno} / {@code GetLastError}, and small marshalling helpers.
 */
public final class FfmSupport
{
   public static final Linker LINKER = Linker.nativeLinker();

   private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
   private static final String LAST_ERROR_NAME = IS_WINDOWS ? "GetLastError" : "errno";

   private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
   private static final Linker.Option CAPTURE_OPTION = Linker.Option.captureCallState(LAST_ERROR_NAME);
   private static final long LAST_ERROR_OFFSET = CAPTURE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement(LAST_ERROR_NAME));

   // Per-thread native memory used to capture errno / GetLastError across downcalls,
   // plus a small scratch area for transient out-parameters (e.g. waitpid's status).
   // The backing automatic arena is kept alive by the segment reference itself.
   private static final ThreadLocal<MemorySegment> CAPTURE_STATE =
      ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(CAPTURE_LAYOUT));
   private static final ThreadLocal<MemorySegment> SCRATCH =
      ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(256, 8));

   private FfmSupport() {
   }

   /**
    * The per-thread segment that must be passed as the leading argument to any
    * method handle produced by {@link #downcall}.
    */
   public static MemorySegment captureState()
   {
      return CAPTURE_STATE.get();
   }

   /**
    * The {@code errno} (POSIX) or {@code GetLastError} (Windows) value captured by the
    * most recent downcall made on this thread.
    */
   public static int getLastError()
   {
      return CAPTURE_STATE.get().get(JAVA_INT, LAST_ERROR_OFFSET);
   }

   /**
    * A small per-thread scratch segment for transient native out-parameters.
    * Callers must not retain the segment across calls that themselves use scratch.
    */
   public static MemorySegment scratch()
   {
      return SCRATCH.get();
   }

   /**
    * Links {@code name} with errno / GetLastError capture. The returned handle takes the
    * {@link #captureState()} segment as an implicit first argument.
    */
   public static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor, Linker.Option... options)
   {
      MemorySegment symbol = lookup.find(name)
         .orElseThrow(() -> new UnsatisfiedLinkError("Failed to find native symbol: " + name));
      Linker.Option[] allOptions = new Linker.Option[options.length + 1];
      allOptions[0] = CAPTURE_OPTION;
      System.arraycopy(options, 0, allOptions, 1, options.length);
      return LINKER.downcallHandle(symbol, descriptor, allOptions);
   }

   /**
    * Links {@code name} with errno / GetLastError capture, or returns {@code null} if the
    * symbol is not present in the running platform's libraries.
    */
   public static MethodHandle downcallOptional(SymbolLookup lookup, String name, FunctionDescriptor descriptor)
   {
      Optional<MemorySegment> symbol = lookup.find(name);
      return symbol.map(s -> LINKER.downcallHandle(s, descriptor, CAPTURE_OPTION)).orElse(null);
   }

   /**
    * Allocates a NULL-terminated {@code char *[]} whose elements are NUL-terminated
    * copies of {@code strings}, suitable for passing as {@code argv} / {@code envp}.
    */
   public static MemorySegment toStringArray(Arena arena, String[] strings)
   {
      MemorySegment array = arena.allocate(ADDRESS, strings.length + 1);
      for (int i = 0; i < strings.length; i++) {
         array.setAtIndex(ADDRESS, i, arena.allocateFrom(strings[i]));
      }
      array.setAtIndex(ADDRESS, strings.length, MemorySegment.NULL);
      return array;
   }
}
