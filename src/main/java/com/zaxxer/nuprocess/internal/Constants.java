package com.zaxxer.nuprocess.internal;

import com.zaxxer.nuprocess.NuProcess;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Constants
{
   private static final Logger LOGGER = Logger.getLogger(Constants.class.getCanonicalName());

   public static final int NUMBER_OF_THREADS;
   public static final int JVM_MAJOR_VERSION;
   static final OperatingSystem OS;

   static final int DEFAULT_BUFFER_CAPACITY = 64 * 1024;
   static final int MIN_BUFFER_CAPACITY = 1024;
   static final int MAX_BUFFER_CAPACITY = 1024 * 1024;

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

    public static int getBufferCapacity() {
        final String bufferCapacityProperty = System.getProperty(NuProcess.BUFFER_CAPACITY_PROPERTY);
        if (bufferCapacityProperty == null || bufferCapacityProperty.trim().isEmpty()) {
            return DEFAULT_BUFFER_CAPACITY;
        }
        try {
            final int value = Integer.parseInt(bufferCapacityProperty);
            if (value < MIN_BUFFER_CAPACITY) {
                LOGGER.log(Level.WARNING, "Requested bufferCapacity of " + value + " is less than min, defaulting to min value of " + MIN_BUFFER_CAPACITY);
                return MIN_BUFFER_CAPACITY;
            }
            else if (value > MAX_BUFFER_CAPACITY) {
                LOGGER.log(Level.WARNING, "Requested bufferCapacity of " + value + " is more than max, defaulting to max value of " + MAX_BUFFER_CAPACITY);
                return MAX_BUFFER_CAPACITY;
            }
            else {
                return value;
            }
        }
        catch (NumberFormatException e) {
            return DEFAULT_BUFFER_CAPACITY;
        }
    }

}
