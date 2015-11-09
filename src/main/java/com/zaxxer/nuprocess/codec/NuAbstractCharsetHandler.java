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
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * Convenience implementation of {@link NuProcessHandler} which decodes stdin,
 * stdout, and stderr bytes to and from Java UTF-16 string data using a
 * {@link Charset} or separate {@link CharsetDecoder} and {@link CharsetEncoder
 * CharsetEncoders}.
 * <p>
 * Subclass this and override any of {@link #onStdinCharsReady(CharBuffer)},
 * {@link #onStdoutChars(CharBuffer, boolean, CoderResult)}, and/or
 * {@link #onStderrChars(CharBuffer, boolean, CoderResult)} to process encoded
 * and decoded string data.
 * <p>
 * You can also override any of the methods from {@link NuProcessHandler}) to
 * customize process handling behavior.
 *
 * @author Ben Hamilton
 */
public abstract class NuAbstractCharsetHandler implements NuProcessHandler
{
   private final NuCharsetEncoder stdinEncoder;
   private final NuCharsetDecoder stdoutDecoder;
   private final NuCharsetDecoder stderrDecoder;

   private class StdinEncoderHandler implements NuCharsetEncoderHandler
   {
      @Override
      public boolean onStdinReady(CharBuffer buffer)
      {
         return onStdinCharsReady(buffer);
      }

      @Override
      public void onEncoderError(CoderResult result)
      {
         onStdinEncoderError(result);
      }
   }

   private class StdoutDecoderHandler implements NuCharsetDecoderHandler
   {
      @Override
      public boolean onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult)
      {
         return onStdoutChars(buffer, closed, decoderResult);
      }
   }

   private class StderrDecoderHandler implements NuCharsetDecoderHandler
   {
      @Override
      public boolean onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult)
      {
         return onStderrChars(buffer, closed, decoderResult);
      }
   }

   /**
    * Constructor which encodes and decodes stdin, stdout, and stderr bytes
    * using the given {@link Charset}.
    *
    * @param charset The {@link Charset} with which to encode and decode stdin,
    *        stdout, and stderr bytes
    */
   protected NuAbstractCharsetHandler(Charset charset)
   {
      this(charset.newEncoder(), charset.newDecoder(), charset.newDecoder());
   }

   /**
    * Constructor which encodes and decodes stdin, stdout, and stderr bytes
    * using specific {@link CharsetEncoder} and {@link CharsetDecoder
    * CharsetDecoders}, then invokes {@link #onStdinCharsReady(CharBuffer)},
    * {@link #onStdoutChars(CharBuffer, boolean, CoderResult)}, and
    * {@link #onStderrChars(CharBuffer, boolean, CoderResult)} to process the
    * encoded and decoded string data.
    *
    * @param stdinEncoder The {@link CharsetEncoder} with which to encode stdin
    *        bytes
    * @param stdoutDecoder The {@link CharsetDecoder} with which to decode
    *        stdout bytes
    * @param stderrDecoder The {@link CharsetDecoder} with which to decode
    *        stderr bytes
    */
   protected NuAbstractCharsetHandler(CharsetEncoder stdinEncoder, CharsetDecoder stdoutDecoder, CharsetDecoder stderrDecoder)
   {
      this.stdinEncoder = new NuCharsetEncoder(new StdinEncoderHandler(), stdinEncoder);
      this.stdoutDecoder = new NuCharsetDecoder(new StdoutDecoderHandler(), stdoutDecoder);
      this.stderrDecoder = new NuCharsetDecoder(new StderrDecoderHandler(), stderrDecoder);
   }

   /**
    * Override this to provide Unicode Java string data to stdin.
    *
    * @param buffer The {@link CharBuffer} into which you should write string
    *        data to be fed to stdin
    * @return {@code true} if you have more string data to feed to stdin
    */
   protected boolean onStdinCharsReady(CharBuffer buffer)
   {
      return false;
   }

   /**
    * Override this to handle errors encoding string data received from
    * {@link #onStdinCharsReady(CharBuffer)}.
    *
    * @param result The {@link CoderResult} indicating encoder error
    */
   protected void onStdinEncoderError(CoderResult result)
   {
   }

   /**
    * Override this to receive decoded Unicode Java string data read from
    * stdout.
    * <p>
    * Make sure to set the {@link CharBuffer#position() position} of
    * {@code buffer} to indicate how much data you have read before returning.
    *
    * @param buffer The {@link CharBuffer} receiving Unicode string data.
    */
   protected boolean onStdoutChars(CharBuffer buffer, boolean closed, CoderResult coderResult)
   {
      // Consume the entire buffer by default.
      buffer.position(buffer.limit());
      return !closed;
   }

   /**
    * Override this to receive decoded Unicode Java string data read from
    * stderr.
    * <p>
    * Make sure to set the {@link CharBuffer#position() position} of
    * {@code buffer} to indicate how much data you have read before returning.
    *
    * @param buffer The {@link CharBuffer} receiving Unicode string data.
    */
   protected boolean onStderrChars(CharBuffer buffer, boolean closed, CoderResult coderResult)
   {
      // Consume the entire buffer by default.
      buffer.position(buffer.limit());
      return !closed;
   }

   /** {@inheritDoc} */
   @Override
   public void onPreStart(NuProcess nuProcess)
   {
   }

   /** {@inheritDoc} */
   @Override
   public void onStart(NuProcess nuProcess)
   {
   }

   /** {@inheritDoc} */
   @Override
   public void onExit(int exitCode)
   {
   }

   /** {@inheritDoc} */
   @Override
   public final boolean onStdout(ByteBuffer buffer, boolean closed)
   {
      stdoutDecoder.onOutput(buffer, closed);
      return true;
   }

   /** {@inheritDoc} */
   @Override
   public final boolean onStderr(ByteBuffer buffer, boolean closed)
   {
      stderrDecoder.onOutput(buffer, closed);
      return true;
   }

   /** {@inheritDoc} */
   @Override
   public final boolean onStdinReady(ByteBuffer buffer)
   {
      return stdinEncoder.onStdinReady(buffer);
   }
}
