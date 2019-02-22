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

   HANDLE INVALID_HANDLE_VALUE = new HANDLE(Pointer.createConstant(Native.POINTER_SIZE == 8 ? -1 : 0xFFFFFFFFL));

   class HANDLE extends PointerType
   {
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
         Object o = super.fromNative(nativeValue, context);
         if (INVALID_HANDLE_VALUE.equals(o)) {
            return INVALID_HANDLE_VALUE;
         }
         return o;
      }
   }

   static class WORD extends IntegerType
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

   static class DWORD extends IntegerType
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

   static class ULONG_PTR extends IntegerType
   {
      public ULONG_PTR()
      {
         this(0);
      }

      public ULONG_PTR(long value)
      {
         super(Native.POINTER_SIZE, value, true);
      }

      public Pointer toPointer()
      {
         return Pointer.createConstant(longValue());
      }
   }

   static class ULONG_PTRByReference extends ByReference
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

   class SECURITY_ATTRIBUTES extends Structure
   {
      public DWORD dwLength;
      public Pointer lpSecurityDescriptor;
      public boolean bInheritHandle;

      @Override
      @SuppressWarnings("rawtypes")
      protected List getFieldOrder()
      {
         return Arrays.asList(new String[] { "dwLength", "lpSecurityDescriptor", "bInheritHandle" });
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

      @Override
      @SuppressWarnings("rawtypes")
      protected List getFieldOrder()
      {
         return Arrays.asList(new String[] { "cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize", "dwXCountChars", "dwYCountChars",
               "dwFillAttribute", "dwFlags", "wShowWindow", "cbReserved2", "lpReserved2", "hStdInput", "hStdOutput", "hStdError" });
      }

      public STARTUPINFO()
      {
         cb = new DWORD(size());
      }
   }

   class PROCESS_INFORMATION extends Structure
   {
      public HANDLE hProcess;
      public HANDLE hThread;
      public DWORD dwProcessId;
      public DWORD dwThreadId;

      @Override
      @SuppressWarnings("rawtypes")
      protected List getFieldOrder()
      {
         return Arrays.asList(new String[] { "hProcess", "hThread", "dwProcessId", "dwThreadId" });
      }
   }
}
