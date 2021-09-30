/*
 * Copyright (C) 2015 Ben Hamilton
 *
 * Originally from JNA com.sun.jna.platform.win32 package (Apache
 * License, Version 2.0)
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

package com.zaxxer.nuprocess.windows;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.*;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.ptr.ByteByReference;

/**
 * Constants and structures for Windows APIs, borrowed from com.sun.jna.platform.win32
 * to avoid pulling in a dependency on that package.
 */
@SuppressWarnings("serial")
public interface NuWinNT
{
   int CREATE_SUSPENDED = 0x00000004;
   int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
   int CREATE_NO_WINDOW = 0x08000000;
   int CREATE_NEW_PROCESS_GROUP = 0x00000200;

   int ERROR_SUCCESS = 0;
   int ERROR_BROKEN_PIPE = 109;
   int ERROR_PIPE_NOT_CONNECTED = 233;
   int ERROR_PIPE_CONNECTED = 535;
   int ERROR_IO_PENDING = 997;

   int FILE_ATTRIBUTE_NORMAL = 0x00000080;
   int FILE_FLAG_OVERLAPPED = 0x40000000;
   int FILE_SHARE_READ = 0x00000001;
   int FILE_SHARE_WRITE = 0x00000002;

   int GENERIC_READ = 0x80000000;
   int GENERIC_WRITE = 0x40000000;

   int OPEN_EXISTING = 3;

   int STATUS_PENDING = 0x00000103;
   int STILL_ACTIVE = STATUS_PENDING;

   int STARTF_USESTDHANDLES = 0x100;

   int JOB_OBJECT_LIMIT_BREAKAWAY_OK = 2048;
   int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 8192;
   // see SetInformationJobObject at msdn
   int JobObjectExtendedLimitInformation = 9;
   // see SetInformationJobObject at msdn
   int JobObjectBasicUIRestrictions = 4;
   // 0x00000020
   int JOB_OBJECT_UILIMIT_GLOBALATOMS = 0x00000020;
   // 0x01000000
   int CREATE_BREAKAWAY_FROM_JOB = 16777216;

   HANDLE INVALID_HANDLE_VALUE = new HANDLE(HANDLE.INVALID);

   class HANDLE extends PointerType
   {
      static final Pointer INVALID = Pointer.createConstant(Native.POINTER_SIZE == 8 ? -1 : 0xFFFFFFFFL);

      public HANDLE()
      {
      }

      public HANDLE(Pointer p)
      {
         setPointer(p);
      }

      @Override
      public Object fromNative(Object nativeValue, FromNativeContext context)
      {
         if (nativeValue == null) {
            return null;
         }

         Pointer ptr = (Pointer) nativeValue;
         if (INVALID.equals(ptr)) {
            return INVALID_HANDLE_VALUE;
         }
         return new HANDLE(ptr);
      }
   }

   class WORD extends IntegerType
   {
      public static final int SIZE = 2;

      public WORD()
      {
         this(0);
      }

      public WORD(long value)
      {
         super(SIZE, value, true);
      }
   }

   class DWORD extends IntegerType
   {
      public static final int SIZE = 4;

      public DWORD()
      {
         this(0);
      }

      public DWORD(long value)
      {
         super(SIZE, value, true);
      }
   }

   class ULONG_PTR extends IntegerType
   {
      public ULONG_PTR()
      {
         this(0);
      }

      public ULONG_PTR(long value)
      {
         super(Native.POINTER_SIZE, value, true);
      }
   }

   class ULONG_PTRByReference extends ByReference
   {
      public ULONG_PTRByReference()
      {
         this(new ULONG_PTR(0));
      }

      public ULONG_PTRByReference(ULONG_PTR value)
      {
         super(Native.POINTER_SIZE);
         setValue(value);
      }

      public void setValue(ULONG_PTR value)
      {
         if (Native.POINTER_SIZE == 4) {
            getPointer().setInt(0, value.intValue());
         }
         else {
            getPointer().setLong(0, value.longValue());
         }
      }

      public ULONG_PTR getValue()
      {
         return new ULONG_PTR(Native.POINTER_SIZE == 4 ? getPointer().getInt(0) : getPointer().getLong(0));
      }
   }

   class ULONGLONG extends IntegerType implements Comparable<ULONGLONG>
   {

      /**
       * The Constant SIZE.
       */
      public static final int SIZE = Native.LONG_SIZE * 2;

      /**
       * Instantiates a new ULONGLONG.
       */
      public ULONGLONG()
      {
         this(0);
      }

      /**
       * Instantiates a new ULONGLONG.
       *
       * @param value the value
       */
      public ULONGLONG(long value)
      {
         super(SIZE, value, true);
      }

      @Override public int compareTo(ULONGLONG other)
      {
         return compare(this, other);
      }
   }

   class SIZE_T extends ULONG_PTR
   {
      public SIZE_T()
      {
         this(0);
      }

      public SIZE_T(long value)
      {
         super(value);
      }
   }

   class SECURITY_ATTRIBUTES extends Structure
   {
      public DWORD dwLength;
      public Pointer lpSecurityDescriptor;
      public boolean bInheritHandle;

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("dwLength", "lpSecurityDescriptor", "bInheritHandle");
      }
   }

   class STARTUPINFO extends Structure
   {
      public DWORD cb;
      public String lpReserved;
      public String lpDesktop;
      public String lpTitle;
      public DWORD dwX;
      public DWORD dwY;
      public DWORD dwXSize;
      public DWORD dwYSize;
      public DWORD dwXCountChars;
      public DWORD dwYCountChars;
      public DWORD dwFillAttribute;
      public int dwFlags;
      public WORD wShowWindow;
      public WORD cbReserved2;
      public ByteByReference lpReserved2;
      public HANDLE hStdInput;
      public HANDLE hStdOutput;
      public HANDLE hStdError;

      public STARTUPINFO()
      {
         cb = new DWORD(size());
      }

      @Override
      protected List<String> getFieldOrder()
      {
         return Arrays.asList("cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize",
               "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow", "cbReserved2",
               "lpReserved2", "hStdInput", "hStdOutput", "hStdError");
      }
   }

   class PROCESS_INFORMATION extends Structure
   {
      public HANDLE hProcess;
      public HANDLE hThread;
      public DWORD dwProcessId;
      public DWORD dwThreadId;

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("hProcess", "hThread", "dwProcessId", "dwThreadId");
      }
   }

   class LARGE_INTEGER extends Structure implements Comparable<LARGE_INTEGER>
   {
      public static class ByReference extends LARGE_INTEGER implements Structure.ByReference
      {
      }

      public static class LowHigh extends Structure
      {
         public DWORD LowPart;
         public DWORD HighPart;

         public LowHigh()
         {
            super();
         }

         public LowHigh(long value)
         {
            this(new DWORD(value & 0xFFFFFFFFL), new DWORD((value >> 32) & 0xFFFFFFFFL));
         }

         public LowHigh(DWORD low, DWORD high)
         {
            LowPart = low;
            HighPart = high;
         }

         public long longValue()
         {
            long loValue = LowPart.longValue();
            long hiValue = HighPart.longValue();
            return ((hiValue << 32) & 0xFFFFFFFF00000000L) | (loValue & 0xFFFFFFFFL);
         }

         @Override public String toString()
         {
            if ((LowPart == null) || (HighPart == null)) {
               return "null";
            }
            else {
               return Long.toString(longValue());
            }
         }

         @Override protected List<String> getFieldOrder()
         {
            return Arrays.asList("LowPart", "HighPart");
         }
      }

      public static class UNION extends Union
      {
         public LowHigh lh;
         public long value;

         public UNION()
         {
            super();
         }

         public UNION(long value)
         {
            this.value = value;
            this.lh = new LowHigh(value);
         }

         public long longValue()
         {
            return value;
         }

         @Override public String toString()
         {
            return Long.toString(longValue());
         }
      }

      public UNION u;

      public LARGE_INTEGER()
      {
         super();
      }

      public LARGE_INTEGER(long value)
      {
         this.u = new UNION(value);
      }

      /**
       * Low DWORD.
       *
       * @return Low DWORD value
       */
      public DWORD getLow()
      {
         return u.lh.LowPart;
      }

      /**
       * High DWORD.
       *
       * @return High DWORD value
       */
      public DWORD getHigh()
      {
         return u.lh.HighPart;
      }

      /**
       * 64-bit value.
       *
       * @return The 64-bit value.
       */
      public long getValue()
      {
         return u.value;
      }

      @Override public int compareTo(LARGE_INTEGER other)
      {
         return compare(this, other);
      }

      @Override public String toString()
      {
         return (u == null) ? "null" : Long.toString(getValue());
      }

      /**
       * Compares 2 LARGE_INTEGER values -  - <B>Note:</B> a {@code null}
       * value is considered <U>greater</U> than any non-{@code null} one
       * (i.e., {@code null} values are &quot;pushed&quot; to the end
       * of a sorted array / list of values)
       *
       * @param v1 The 1st value
       * @param v2 The 2nd value
       * @return 0 if values are equal (including if <U>both</U> are {@code null},
       * negative if 1st value less than 2nd one, positive otherwise. <B>Note:</B>
       * the comparison uses the {@link #getValue()}.
       * @see IntegerType#compare(long, long)
       */
      public static int compare(LARGE_INTEGER v1, LARGE_INTEGER v2)
      {
         if (v1 == v2) {
            return 0;
         }
         else if (v1 == null) {
            return 1;   // v2 cannot be null or v1 == v2 would hold
         }
         else if (v2 == null) {
            return (-1);
         }
         else {
            return IntegerType.compare(v1.getValue(), v2.getValue());
         }
      }

      /**
       * Compares a LARGE_INTEGER value with a {@code long} one. <B>Note:</B> if
       * the LARGE_INTEGER value is {@code null} then it is consider <U>greater</U>
       * than any {@code long} value.
       *
       * @param v1 The {@link LARGE_INTEGER} value
       * @param v2 The {@code long} value
       * @return 0 if values are equal, negative if 1st value less than 2nd one,
       * positive otherwise. <B>Note:</B> the comparison uses the {@link #getValue()}.
       * @see IntegerType#compare(long, long)
       */
      public static int compare(LARGE_INTEGER v1, long v2)
      {
         if (v1 == null) {
            return 1;
         }
         else {
            return IntegerType.compare(v1.getValue(), v2);
         }
      }

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("u");
      }
   }

   class JOBJECT_BASIC_LIMIT_INFORMATION extends Structure
   {
      public LARGE_INTEGER PerProcessUserTimeLimit;
      public LARGE_INTEGER PerJobUserTimeLimit;
      public int LimitFlags;
      public SIZE_T MinimumWorkingSetSize;
      public SIZE_T MaximumWorkingSetSize;
      public int ActiveProcessLimit;
      public ULONG_PTR Affinity;
      public int PriorityClass;
      public int SchedulingClass;

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize", "MaximumWorkingSetSize",
                              "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass");
      }
   }

   class IO_COUNTERS extends Structure
   {

      public ULONGLONG ReadOperationCount;
      public ULONGLONG WriteOperationCount;
      public ULONGLONG OtherOperationCount;
      public ULONGLONG ReadTransferCount;
      public ULONGLONG WriteTransferCount;
      public ULONGLONG OtherTransferCount;

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("ReadOperationCount", "WriteOperationCount", "OtherOperationCount", "ReadTransferCount", "WriteTransferCount",
                              "OtherTransferCount");
      }
   }

   class JOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure
   {

      public JOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
      public IO_COUNTERS IoInfo;
      public SIZE_T ProcessMemoryLimit;
      public SIZE_T JobMemoryLimit;
      public SIZE_T PeakProcessMemoryUsed;
      public SIZE_T PeakJobMemoryUsed;

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed");
      }

      public JOBJECT_EXTENDED_LIMIT_INFORMATION()
      {
      }

      public JOBJECT_EXTENDED_LIMIT_INFORMATION(Pointer memory)
      {
         super(memory);
      }

      public static class ByReference extends JOBJECT_EXTENDED_LIMIT_INFORMATION implements Structure.ByReference
      {

         public ByReference()
         {
         }

         public ByReference(Pointer memory)
         {
            super(memory);
         }
      }
   }

   class JOBOBJECT_BASIC_UI_RESTRICTIONS extends Structure
   {
      public int UIRestrictionsClass;

      public JOBOBJECT_BASIC_UI_RESTRICTIONS()
      {
      }

      public JOBOBJECT_BASIC_UI_RESTRICTIONS(Pointer memory)
      {
         super(memory);
      }

      public static class ByReference extends JOBOBJECT_BASIC_UI_RESTRICTIONS implements Structure.ByReference
      {
         public ByReference()
         {
         }

         public ByReference(Pointer memory)
         {
            super(memory);
         }
      }

      @Override protected List<String> getFieldOrder()
      {
         return Arrays.asList("UIRestrictionsClass");
      }
   }
}
