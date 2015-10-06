/*
 * Copyright (C) 2015 Ben Hamilton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.nuprocess.internal;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.List;

public class LinuxLibC
{
   static {
      Native.register(Platform.C_LIBRARY_NAME);
   }

   // from /usr/include/sched.h
   public static native int unshare(int flags);

   // from /usr/include/bits/sched.h
   public static final int CLONE_FS = 0x00000200; /* Share or unshare cwd between threads / processes */

   // /usr/include/sys/signalfd.h
   public static native int signalfd(int fd, LibC.SigsetT mask, int flags);

   public static final int SIGCHLD = 17;
   public static final int SFD_CLOEXEC = 02000000;
   public static final int SFD_NONBLOCK = 00004000;
   public static class SignalFdSiginfo extends Structure {                                                                         
      public int ssi_signo;   /* Signal number */                                                     
      public int ssi_errno;   /* Error number (unused) */                                             
      public int ssi_code;    /* Signal code */                                                       
      public int ssi_pid;     /* PID of sender */                                                     
      public int ssi_uid;     /* Real UID of sender */                                                
      public int ssi_fd;      /* File descriptor (SIGIO) */                                           
      public int ssi_tid;     /* Kernel timer ID (POSIX timers) */
      public int ssi_band;    /* Band event (SIGIO) */                                                
      public int ssi_overrun; /* POSIX timer overrun count */                                         
      public int ssi_trapno;  /* Trap number that caused signal */                                    
      public int ssi_status;  /* Exit status or signal (SIGCHLD) */                                   
      public int ssi_int;     /* Integer sent by sigqueue(3) */                                       
      public long ssi_ptr;     /* Pointer sent by sigqueue(3) */                                       
      public long ssi_utime;   /* User CPU time consumed (SIGCHLD) */                                  
      public long ssi_stime;   /* System CPU time consumed (SIGCHLD) */                                
      public long ssi_addr;    /* Address that generated signal                                        
				  (for hardware-generated signals) */                                  

      public SignalFdSiginfo() {
	 super();
	 allocateMemory(128);
      }

      @SuppressWarnings("rawtypes")
      @Override
      protected List getFieldOrder()
      {
         return Arrays.asList(
	    "ssi_signo",
	    "ssi_errno",
	    "ssi_code",
	    "ssi_pid",
	    "ssi_uid",
	    "ssi_fd",
	    "ssi_tid",
	    "ssi_band",
	    "ssi_overrun",
	    "ssi_trapno",
	    "ssi_status",
	    "ssi_int",
	    "ssi_ptr",
	    "ssi_utime",
	    "ssi_stime",
	    "ssi_addr"
	 );
      }
    };                             
}
