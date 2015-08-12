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

import java.nio.CharBuffer;
import java.nio.charset.CoderResult;

/**
 * Callbacks invoked by {@link NuProcessDecoder} with decoded string data.
 *
 * @see NuProcessHandler
 */
public interface NuProcessDecoderHandler {
  /**
   * @see NuProcessHandler#onPreStart(NuProcess)
   * @param nuProcess The process that is starting
   */
  void onPreStart(NuProcess nuProcess);

  /**
   * @see NuProcessHandler#onStart(NuProcess)
   * @param nuProcess The process that started
   */
  void onStart(NuProcess nuProcess);

  /**
   * @see NuProcessHandler#onExit(int)
   * @param exitCode The exit code of the process
   */
  void onExit(int exitCode);

  /**
   * This method is invoked when there is stdout data to process or
   * an the end-of-file (EOF) condition has been reached.  In the
   * case of EOF, the {@code buffer} and {@code stdoutDecoderResult}
   * parameters will be {@code null}; this is your signal that EOF
   * has been reached.
   * <p>
   * You do <i>not</i> need to consume the entire buffer. You may set
   * the buffer's {@code position} to a value less than its {@code limit},
   * in which case the buffer will be {@link CharBuffer#compact() compacted}
   * after returning. Any unused data will be kept at the start of
   * the buffer and passed back to you as part of the next invocation
   * of this method.
   * <p>
   * Exceptions thrown out from your method will be ignored, but your
   * method should handle all exceptions itself.
   *
   * @param buffer a {@link CharBuffer} containing received and decoded
   *               stderr data, or {@code null} signifying that an EOF condition
   *               has been reached
   * @param stdoutDecoderResult a {@link CoderResult} signifying whether
   *                            an error was encountered decoding stdout bytes
   */
  void onStdout(CharBuffer buffer, CoderResult stdoutDecoderResult);

  /**
   * This method is invoked when there is stdout data to process or
   * an the end-of-file (EOF) condition has been reached.  In the
   * case of EOF, the {@code buffer} and {@code stdoutDecoderResult}
   * parameters will be {@code null}; this is your signal that EOF
   * has been reached.
   * <p>
   * You do <i>not</i> need to consume the entire buffer. You may set
   * the buffer's {@code position} to a value less than its {@code limit},
   * in which case the buffer will be {@link CharBuffer#compact() compacted}
   * after returning. Any unused data will be kept at the start of
   * the buffer and passed back to you as part of the next invocation
   * of this method.
   * <p>
   * Exceptions thrown out from your method will be ignored, but your
   * method should handle all exceptions itself.
   *
   * @param buffer a {@link CharBuffer} containing received and decoded
   *               stderr data, or {@code null} signifying that an EOF condition
   *               has been reached
   * @param stderrDecoderResult a {@link CoderResult} signifying whether
   *                            an error was encountered decoding stderr bytes
   */
  void onStderr(CharBuffer buffer, CoderResult stderrDecoderResult);

  /**
   * This method is invoked after you have expressed a desire to write to stdin
   * by first calling {@link NuProcess#wantWrite()}.  When this method is invoked,
   * your code should write data to be sent to the stdin of the child process into
   * the provided {@link CharBuffer}.  After writing data into the {@code buffer}
   * your code <em>must</em> {@link CharBuffer#flip() flip} the buffer before
   * returning.
   * <p>
   * If not all of the data needed to be written will fit in the provided {@code buffer},
   * this method can return {@code true} to indicate a desire to write more data.  If
   * there is no more data to be written at the time this method is invoked, then
   * {@code false} should be returned from this method.  It is always possible to
   * call {@link NuProcess#wantWrite()} later if data becomes available to be written.
   * <p>
   * Note that this method can be invoked one more time after you return {@code false},
   * in case the encoded {@link CharBuffer} did not fit inside a byte buffer.
   *
   * @param buffer a {@link CharBuffer} into which your stdin-bound data should be written
   * @return true if you have more data to write immediately, false otherwise
   */
  boolean onStdinReady(CharBuffer buffer);
}
