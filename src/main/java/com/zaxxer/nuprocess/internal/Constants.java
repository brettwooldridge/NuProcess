package com.zaxxer.nuprocess.internal;

public class Constants
{
   public static final int NUMBER_OF_THREADS;
   static final int JVM_MAJOR_VERSION;
   static final OperatingSystem OS;

   enum OperatingSystem
   {
      MAC,
      LINUX,
      SOLARIS
   }

   static {
      JVM_MAJOR_VERSION = System.getProperty("java.vm.specification.version").equals("1.7") ? 7 : 8;

      final String osname = System.getProperty("os.name").toLowerCase();
      if (osname.contains("mac") || osname.contains("freebsd"))
          OS = OperatingSystem.MAC;
      else if (osname.contains("linux"))
          OS = OperatingSystem.LINUX;
      else
          OS = OperatingSystem.SOLARIS;

      final String threads = System.getProperty("com.zaxxer.nuprocess.threads", "auto");
      if ("auto".equals(threads)) {
          NUMBER_OF_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
      }
      else if ("cores".equals(threads)) {
          NUMBER_OF_THREADS = Runtime.getRuntime().availableProcessors();
      }
      else {
          NUMBER_OF_THREADS = Math.max(1, Integer.parseInt(threads));
      }
   }
}
