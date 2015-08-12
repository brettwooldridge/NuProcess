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

package com.zaxxer.nuprocess;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

/**
 * Implementation of {@link NuProcessHandler} which handles encoding and decoding of
 * stdin, stdout, and stderr process bytes to and from Java UTF-16 string data.
 *
 * Calls back into a {@link NuProcessDecoderHandler} with decoded stdout and
 * stderr string data (or when stdin is ready for string data to be read and
 * encoded to bytes).
 *
 * This class is not intended to be subclassed.
 *
 * @author Ben Hamilton
 */
public final class NuProcessDecoder implements NuProcessHandler {
  private final NuProcessDecoderHandler handler;
  private final CharsetEncoder stdinEncoder;
  private final CharsetDecoder stdoutDecoder;
  private final CharsetDecoder stderrDecoder;
  private final CharBuffer stdinCharBuf;
  private final CharBuffer stdoutCharBuf;
  private final CharBuffer stderrCharBuf;

  /**
   * Creates a decoder which uses a single {@link Charset} for stdin, stdout, and stderr.
   *
   * @param handler {@link NuProcessDecoderHandler} called back with decoded string data
   * @param charset {@link Charset} used to encode and decode stdin, stdout, and stderr
   */
  public NuProcessDecoder(NuProcessDecoderHandler handler, Charset charset) {
    this(handler, charset.newEncoder(), charset.newDecoder(), charset.newDecoder());
  }

  /**
   * Creates a decoder which uses separate {@link CharsetEncoder} and
   * {@link CharsetDecoder CharsetDecoders} for stdin, stdout, and stderr.
   *
   * @param handler {@link NuProcessDecoderHandler} called back with decoded string data
   * @param stdinEncoder {@link CharsetEncoder} used to encode stdin string data to bytes
   * @param stdoutDecoder {@link CharsetDecoder} used to decode stdout bytes to string data
   * @param stderrDecoder {@link CharsetDecoder} used to decode stdin bytes to string data
   */
  public NuProcessDecoder(
      NuProcessDecoderHandler handler,
      CharsetEncoder stdinEncoder,
      CharsetDecoder stdoutDecoder,
      CharsetDecoder stderrDecoder) {
    this.handler = handler;
    this.stdinEncoder = stdinEncoder;
    this.stdoutDecoder = stdoutDecoder;
    this.stderrDecoder = stderrDecoder;
    this.stdinCharBuf = CharBuffer.allocate(NuProcess.BUFFER_CAPACITY);
    this.stdoutCharBuf = CharBuffer.allocate(NuProcess.BUFFER_CAPACITY);
    this.stderrCharBuf = CharBuffer.allocate(NuProcess.BUFFER_CAPACITY);
  }

  /** {@inheritDoc} */
  @Override
  public void onPreStart(NuProcess nuProcess) {
    this.handler.onPreStart(nuProcess);
  }

  /** {@inheritDoc} */
  @Override
  public void onStart(NuProcess nuProcess) {
    this.handler.onStart(nuProcess);
  }

  /** {@inheritDoc} */
  @Override
  public void onPreExitStdout(ByteBuffer stdoutByteBuffer) {
    CoderResult stdoutCoderResult = stdoutDecoder.decode(
        stdoutByteBuffer, stdoutCharBuf, /* endOfInput */ true);
    CoderResult stdoutFlushResult = stdoutDecoder.flush(stdoutCharBuf);
    stdoutCharBuf.flip();
    this.handler.onStdout(
        stdoutCharBuf, stdoutCoderResult.isUnderflow() ? stdoutFlushResult : stdoutCoderResult);
  }

  /** {@inheritDoc} */
  @Override
  public void onPreExitStderr(ByteBuffer stderrByteBuffer) {
    CoderResult stderrCoderResult = stderrDecoder.decode(
        stderrByteBuffer, stderrCharBuf, /* endOfInput */ true);
    CoderResult stderrFlushResult = stderrDecoder.flush(stderrCharBuf);
    stderrCharBuf.flip();
    this.handler.onStderr(
        stderrCharBuf, stderrCoderResult.isUnderflow() ? stderrFlushResult : stderrCoderResult);
  }

  /** {@inheritDoc} */
  @Override
  public void onExit(int exitCode) {
    this.handler.onExit(exitCode);
  }

  /** {@inheritDoc} */
  @Override
  public void onStdout(ByteBuffer buffer) {
    if (buffer == null) {
      this.handler.onStdout(null, null);
      return;
    }

    CoderResult coderResult = stdoutDecoder.decode(buffer, stdoutCharBuf, /* endOfInput */ false);
    stdoutCharBuf.flip();
    this.handler.onStdout(stdoutCharBuf, coderResult);
    stdoutCharBuf.compact();
  }

  /** {@inheritDoc} */
  @Override
  public void onStderr(ByteBuffer buffer) {
    if (buffer == null) {
      this.handler.onStderr(null, null);
      return;
    }

    CoderResult coderResult = stderrDecoder.decode(buffer, stderrCharBuf, /* endOfInput */ false);
    stderrCharBuf.flip();
    this.handler.onStderr(stderrCharBuf, coderResult);
    stderrCharBuf.compact();
  }

  /** {@inheritDoc} */
  @Override
  public boolean onStdinReady(ByteBuffer buffer) {
    // TODO: Should we avoid invoking onStdinReady() when it returned false previously?
    boolean endOfInput = !this.handler.onStdinReady(stdinCharBuf);
    CoderResult encoderResult = stdinEncoder.encode(stdinCharBuf, buffer, endOfInput);
    buffer.flip();
    stdinCharBuf.compact();
    if (encoderResult.isOverflow()) {
      return true;
    } else if (endOfInput) {
      CoderResult flushResult = stdinEncoder.flush(buffer);
      return flushResult.isOverflow();
    } else {
      return true;
    }
  }

  /** {@inheritDoc} */
  @Override
  public boolean shouldCompactBuffersAfterUse() {
    return true;
  }
}
