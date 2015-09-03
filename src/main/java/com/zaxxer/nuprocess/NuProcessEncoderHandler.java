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
 * Callbacks invoked by {@link NuProcessEncoder} with decoded string data.
 *
 * @see NuProcessHandler
 */
public interface NuProcessEncoderHandler {
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

  /**
   * This method is invoked immediately after {@link #onStdinReady(CharBuffer)} returns
   * if encoding the {@link CharBuffer} to bytes fails.
   *
   * @param result The {@link CoderResult} indicating the encoding failure
   */
  void onEncoderError(CoderResult result);
}
