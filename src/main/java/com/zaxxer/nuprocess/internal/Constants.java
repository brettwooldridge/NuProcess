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

   static int getJavaMajorVersion(String versionString) {
       String[] parts = versionString.split("\\.");
       // Make sure we handle versions like '11-ea' which ships with centos7
       int major = Integer.parseInt(parts[0].split("\\D")[0]);
       if (major == 1) {
         major = Integer.parseInt(parts[1].split("\\D")[0]);
       }
       return major;
   }

   static {

      JVM_MAJOR_VERSION = getJavaMajorVersion(System.getProperty("java.version"));

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
