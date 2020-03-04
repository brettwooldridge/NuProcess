package com.zaxxer.nuprocess.internal;

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
}
