package com.zaxxer.nuprocess.linux;

import com.sun.jna.Native;
import org.junit.Assert;
import org.junit.Test;

import static com.sun.jna.Structure.ALIGN_NONE;
import static com.zaxxer.nuprocess.linux.EpollEvent.EpollEventPrototype.detectAlignment;

public class EpollEventTest
{
   // ensure EpollEvent's size matches the platform:
   // - 12 bytes on all 32-bit OSes (4 byte aligned)
   // - 12 bytes on x86-64, where it's compiled with __attribute__((packed)) (1 byte aligned)
   // - 16 bytes on all other 64-bit OSes (8 byte aligned)
   @Test
   public void testSize()
   {
      // ALIGN_NONE is used to emulate __attribute__((packed)) on x86-64, producing a 12 byte struct to
      // match 32-bit platforms (detected by pointer size). On any other 64-bit platform, the struct is
      // aligned to 16 bytes
      int expectedSize = detectAlignment() == ALIGN_NONE || Native.POINTER_SIZE == 4 ? 12 : 16;

      EpollEvent event = new EpollEvent();
      Assert.assertEquals(expectedSize, event.size());
   }
}
