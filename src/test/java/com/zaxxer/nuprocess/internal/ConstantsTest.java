package com.zaxxer.nuprocess.internal;

import com.zaxxer.nuprocess.NuProcess;
import org.junit.Assert;
import org.junit.Test;

public class ConstantsTest {
  @Test
  public void parsesVersionStrings() {
    Assert.assertEquals(8, Constants.getJavaMajorVersion("1.8.0_foo"));
    Assert.assertEquals(8, Constants.getJavaMajorVersion("1.8_foo"));
    Assert.assertEquals(11, Constants.getJavaMajorVersion("11_foo"));
    Assert.assertEquals(8, Constants.getJavaMajorVersion("1.8.0-foo"));
    Assert.assertEquals(8, Constants.getJavaMajorVersion("1.8-foo"));
    Assert.assertEquals(11, Constants.getJavaMajorVersion("11-foo"));
    Assert.assertEquals(8, Constants.getJavaMajorVersion("1.8.0"));
    Assert.assertEquals(8, Constants.getJavaMajorVersion("1.8"));
    Assert.assertEquals(11, Constants.getJavaMajorVersion("11"));
  }

  @Test
  public void checkDefaultBufferCapacity() {
    System.setProperty(NuProcess.BUFFER_CAPACITY_PROPERTY, "");
    Assert.assertEquals(Constants.DEFAULT_BUFFER_CAPACITY, Constants.getBufferCapacity());
  }

  @Test
  public void settingUnparsableBufferCapacityDefaultsToDefault() {
    System.setProperty(NuProcess.BUFFER_CAPACITY_PROPERTY, "foo");
    Assert.assertEquals(Constants.DEFAULT_BUFFER_CAPACITY, Constants.getBufferCapacity());
  }

  @Test
  public void settingSmallerBufferCapacityDefaultsToMin() {
    System.setProperty(NuProcess.BUFFER_CAPACITY_PROPERTY, String.valueOf(Constants.MIN_BUFFER_CAPACITY - 1));
    Assert.assertEquals(Constants.MIN_BUFFER_CAPACITY, Constants.getBufferCapacity());
  }

  @Test
  public void settingLargerBufferCapacityDefaultsToMax() {
    System.setProperty(NuProcess.BUFFER_CAPACITY_PROPERTY, String.valueOf(Constants.MAX_BUFFER_CAPACITY + 1));
    Assert.assertEquals(Constants.MAX_BUFFER_CAPACITY, Constants.getBufferCapacity());
  }

  @Test
  public void settingBufferCapacityNormalCase() {
    int value = Constants.MIN_BUFFER_CAPACITY + (Constants.MAX_BUFFER_CAPACITY - Constants.MIN_BUFFER_CAPACITY) / 2;
    System.setProperty(NuProcess.BUFFER_CAPACITY_PROPERTY, String.valueOf(value));
    Assert.assertEquals(value, Constants.getBufferCapacity());
  }

}
