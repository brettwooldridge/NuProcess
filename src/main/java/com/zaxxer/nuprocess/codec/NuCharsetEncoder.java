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
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * Implementation of {@link NuProcessHandler#onStdinReady(ByteBuffer)} which
 * handles encoding of stdin bytes to Java UTF-16 string data.
 *
 * Calls back into a {@link NuCharsetEncoderHandler} with a {@link CharBuffer}
 * whose contents will be encoded with a specified {@link Charset} or
 * {@link CharsetEncoder} then passed to the stdin of a process.
 *
 * This class is not intended to be subclassed.
 *
 * @author Ben Hamilton
 */
public final class NuCharsetEncoder
{
   private final NuCharsetEncoderHandler handler;
   private final CharsetEncoder encoder;
   private final CharBuffer charBuffer;

   /**
    * Creates an encoder which uses a single {@link Charset} to encode input
    * data.
    *
    * @param handler {@link NuCharsetEncoderHandler} called back with a string
    *        buffer to be encoded and fed to stdin
    * @param charset {@link Charset} used to encode stdin data to bytes
    */
   public NuCharsetEncoder(NuCharsetEncoderHandler handler, Charset charset)
   {
      this(handler, charset.newEncoder());
   }

   /**
    * Creates an encoder which uses a {@link CharsetEncoder} to encode input
    * data.
    *
    * @param handler {@link NuCharsetEncoderHandler} called back with a string
    *        buffer into which the caller writes string data to be written to
    *        stdin
    * @param encoder {@link CharsetEncoder} used to encode stdin string data to
    *        bytes
    */
   public NuCharsetEncoder(NuCharsetEncoderHandler handler, CharsetEncoder encoder)
   {
      this.handler = handler;
      this.encoder = encoder;
      this.charBuffer = CharBuffer.allocate(NuProcess.BUFFER_CAPACITY);
   }

   /**
    * Implementation of {@link NuProcessHandler#onStdinReady(ByteBuffer)} which
    * calls {@link handler} with a string buffer then encodes it to bytes and
    * feeds it to the process's stdin.
    *
    * @param buffer The {@link ByteBuffer} passed to
    *        {@link NuProcessHandler#onStdinReady(ByteBuffer)}
    * @return true if more data needs to be passed to stdin, false otherwise
    */
   public boolean onStdinReady(ByteBuffer buffer)
   {
      // TODO: Should we avoid invoking onStdinReady() when it returned false previously?
      boolean endOfInput = !this.handler.onStdinReady(charBuffer);
      CoderResult encoderResult = encoder.encode(charBuffer, buffer, endOfInput);
      buffer.flip();
      charBuffer.compact();
      if (encoderResult.isError()) {
         this.handler.onEncoderError(encoderResult);
      }
      if (encoderResult.isOverflow()) {
         return true;
      }
      else if (endOfInput) {
         CoderResult flushResult = encoder.flush(buffer);
         return flushResult.isOverflow();
      }
      else {
         return true;
      }
   }
}
