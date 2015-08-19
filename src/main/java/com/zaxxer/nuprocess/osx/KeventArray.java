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

package com.zaxxer.nuprocess.osx;

import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Ben Hamilton
 */
final class KeventArray
{
   private final int size;
   private final Pointer pointer;
   private final List<Kevent> kevents;

   public KeventArray(int size)
   {
      this.size = size;
      long memory = Native.malloc(32 * size);
      pointer = new Pointer(memory);
      kevents = new ArrayList<Kevent>(size);
      for (int i = 0; i < size; i++) {
         kevents.add(new Kevent(pointer.share(i * 32)));
      }
   }

   public void free()
   {
      Native.free(Pointer.nativeValue(pointer));
      kevents.clear();
   }

   public Pointer getPointer() {
      return pointer;
   }

   public Kevent get(int i) {
      return kevents.get(i);
   }

  public int size() {
    return size;
  }
}
