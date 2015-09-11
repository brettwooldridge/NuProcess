/*
 * Copyright (C) 2015 Brett Wooldridge
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

import java.util.Formatter;

/**
 * Hex Dump Elf
 *
 * xxxx: 00 11 22 33 44 55 66 77   88 99 aa bb cc dd ee ff ................
 */
public final class HexDumpElf
{
   private static final int MAX_VISIBLE = 127;
   private static final int MIN_VISIBLE = 31;

   private HexDumpElf()
   {
      // private constructor
   }

   /**
    * @param displayOffset the display offset (left column)
    * @param data the byte array of data
    * @param offset the offset to start dumping in the byte array
    * @param len the length of data to dump
    * @return the dump string
    */
   public static String dump(final int displayOffset, final byte[] data, final int offset, final int len)
   {
      StringBuilder sb = new StringBuilder();
      Formatter formatter = new Formatter(sb);
      StringBuilder ascii = new StringBuilder();

      int dataNdx = offset;
      final int maxDataNdx = offset + len;
      final int lines = (len + 16) / 16;
      for (int i = 0; i < lines; i++) {
         ascii.append(" |");
         formatter.format("%08x  ", displayOffset + (i * 16));

         for (int j = 0; j < 16; j++) {
            if (dataNdx < maxDataNdx) {
               byte b = data[dataNdx++];
               formatter.format("%02x ", b);
               ascii.append((b > MIN_VISIBLE && b < MAX_VISIBLE) ? (char) b : ' ');
            }
            else {
               sb.append("   ");
            }

            if (j == 7) {
               sb.append(' ');
            }
         }

         ascii.append('|');
         sb.append(ascii).append('\n');
         ascii.setLength(0);
      }

      formatter.close();
      return sb.toString();
   }
}