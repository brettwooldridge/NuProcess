package com.zaxxer.nuprocess.linux;

import com.sun.jna.Platform;
import org.junit.Assert;
import org.junit.Test;

public class EpollEventTest
{
   // ensure EpollEvent's size matches the platform:
   // - 12 bytes on all 32-bit architectures (4 byte aligned)
   // - 12 bytes on x86-64, where it's compiled with __attribute__((packed)) (1 byte aligned)
   // - 16 bytes on all other 64-bit architectures (8 byte aligned)
   @Test
   public void testSize()
   {
      // 64-bit architectures use a 16 byte struct, except on AMD/Intel, where the struct is 12 bytes
      // on both 32- and 64-bit. The struct is 12 bytes on all 32-bit architectures
      int expectedSize = (Platform.is64Bit() && !Platform.isIntel()) ? 16 : 12;

      EpollEvent event = new EpollEvent();
      Assert.assertEquals(expectedSize, event.size());
   }
}
