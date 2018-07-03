package com.zaxxer.nuprocess.internal;

public class Constants
{
   public static final int NUMBER_OF_THREADS;
   public static final int JVM_MAJOR_VERSION;
   static final OperatingSystem OS;

   enum OperatingSystem
   {
      MAC,
      LINUX,
      SOLARIS
   }

   static {
      String[] parts = System.getProperty("java.version").split("\\.");
      int major = Integer.valueOf(parts[0]);
      if (major == 1)
        major = Integer.valueOf(parts[1]);
      JVM_MAJOR_VERSION = major;

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
