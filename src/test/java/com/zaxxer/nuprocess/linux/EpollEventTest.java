package com.zaxxer.nuprocess.linux;

import org.junit.Assert;
import org.junit.Test;

public class EpollEventTest
{
   // ensure EpollEvent is 12 bytes, to match its size in C
   @Test
   public void testSize()
   {
      EpollEvent event = new EpollEvent();
      Assert.assertEquals(12, event.size());
   }
}