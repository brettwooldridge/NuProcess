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

package com.zaxxer.nuprocess.codec;

import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessHandler;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

/**
 * Implementation of {@link NuProcessHandler#onStdout(ByteBuffer, boolean)} or
 * {@link NuProcessHandler#onStderr(ByteBuffer, boolean)} which handles decoding
 * of stdout or stderr bytes to Java UTF-16 string data.
 *
 * Calls back into a {@link NuCharsetDecoderHandler} with decoded stdout or
 * stderr string data.
 *
 * This class is not intended to be subclassed.
 *
 * @author Ben Hamilton
 */
public final class NuCharsetDecoder
{
   private final NuCharsetDecoderHandler handler;
   private final CharsetDecoder decoder;
   private final CharBuffer charBuffer;

   /**
    * Creates a decoder which uses a single {@link Charset} to decode output
    * data.
    *
    * @param handler {@link NuCharsetDecoderHandler} called back with decoded
    *        string data
    * @param charset {@link Charset} used to decode output data
    */
   public NuCharsetDecoder(NuCharsetDecoderHandler handler, Charset charset)
   {
      this(handler, charset.newDecoder());
   }

   /**
    * Creates a decoder which uses a {@link CharsetDecoder CharsetDecoders} to
    * decode output data.
    *
    * @param handler {@link NuCharsetDecoderHandler} called back with decoded
    *        string data
    * @param decoder {@link CharsetDecoder} used to decode stdout bytes to
    *        string data
    */
   public NuCharsetDecoder(NuCharsetDecoderHandler handler, CharsetDecoder decoder)
   {
      this.handler = handler;
      this.decoder = decoder;
      this.charBuffer = CharBuffer.allocate(NuProcess.BUFFER_CAPACITY);
   }

   /**
    * Implementation of {@link NuProcessHandler#onStdout(ByteBuffer, boolean)}
    * or {@link NuProcessHandler#onStderr(ByteBuffer, boolean)} which decodes
    * output data and forwards it to {@code handler}.
    *
    * @param buffer {@link ByteBuffer} which received bytes from stdout or
    *        stderr
    * @param closed true if stdout or stderr was closed, false otherwise
    */
   public void onOutput(ByteBuffer buffer, boolean closed)
   {
      CoderResult coderResult = decoder.decode(buffer, charBuffer, /* endOfInput */ closed);
      charBuffer.flip();
      this.handler.onDecode(charBuffer, closed, coderResult);
      charBuffer.compact();
   }
}
