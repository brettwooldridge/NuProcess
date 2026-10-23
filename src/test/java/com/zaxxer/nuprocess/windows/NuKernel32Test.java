package com.zaxxer.nuprocess.windows;

import org.junit.Before;
import org.junit.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class NuKernel32Test
{
   @Before
   public void requireWindows()
   {
      assumeTrue(System.getProperty("os.name").startsWith("Windows"));
   }

   @Test
   public void createsAndClosesCompletionPortsWithNullAndExplicitNullSegments()
   {
      for (MemorySegment existing : new MemorySegment[] {null, MemorySegment.NULL}) {
         MemorySegment port = NuKernel32.CreateIoCompletionPort(NuWinNT.INVALID_HANDLE_VALUE, existing, 0L, 1);
         assertFalse(NuWinNT.isNullHandle(port));
         assertTrue(NuKernel32.CloseHandle(port));
      }
   }

   @Test
   public void connectsWithNullAndExplicitNullOverlappedSegments()
   {
      for (MemorySegment overlapped : new MemorySegment[] {null, MemorySegment.NULL}) {
         assertEquals(0, NuKernel32.ConnectNamedPipe(MemorySegment.NULL, overlapped));
         assertNotEquals(0, NuKernel32.getLastError());
      }
   }

   @Test
   public void readsWithNullAndAllocatedCountSegments()
   {
      try (Arena arena = Arena.ofConfined()) {
         for (MemorySegment count : new MemorySegment[] {null, arena.allocate(ValueLayout.JAVA_INT)}) {
            assertEquals(0, NuKernel32.ReadFile(MemorySegment.NULL, ByteBuffer.allocateDirect(1), 1,
                                              count, MemorySegment.NULL));
            assertNotEquals(0, NuKernel32.getLastError());
         }
      }
   }

   @Test
   public void writesWithNullAndAllocatedCountSegments()
   {
      try (Arena arena = Arena.ofConfined()) {
         for (MemorySegment count : new MemorySegment[] {null, arena.allocate(ValueLayout.JAVA_INT)}) {
            assertEquals(0, NuKernel32.WriteFile(MemorySegment.NULL, ByteBuffer.allocateDirect(1), 1,
                                               count, MemorySegment.NULL));
            assertNotEquals(0, NuKernel32.getLastError());
         }
      }
   }
}
