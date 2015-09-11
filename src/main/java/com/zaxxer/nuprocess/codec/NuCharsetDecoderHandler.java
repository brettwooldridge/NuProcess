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

import com.zaxxer.nuprocess.NuProcessHandler;

import java.nio.CharBuffer;
import java.nio.charset.CoderResult;

/**
 * Callbacks invoked by {@link NuCharsetDecoder} with decoded string data.
 *
 * @see NuProcessHandler
 */
public interface NuCharsetDecoderHandler
{
   /**
    * This method is invoked when there is decoded data to process or an the
    * end-of-file (EOF) condition has been reached. In the case of EOF, the
    * {@code closed} parameter will be {@code true}; this is your signal that
    * EOF has been reached.
    * <p>
    * You do not own the {@link CharBuffer} provided to you. You should not
    * retain a reference to this buffer.
    * <p>
    * Upon returning from this method, if any characters are left in the buffer
    * (i.e., {@code buffer.hasRemaining()} returns {@code true}), then the
    * buffer will be {@link CharBuffer#compact() compacted} after returning. Any
    * unused data will be kept at the start of the buffer and passed back to you
    * as part of the next invocation of this method (which might be when EOF is
    * reached and {@code closed} is {@code true}).
    * <p>
    * Exceptions thrown out from your method will be ignored, but your method
    * should handle all exceptions itself.
    *
    * @param buffer a {@link CharBuffer} containing received and decoded data
    * @param closed {@code true} if EOF has been reached
    * @param decoderResult a {@link CoderResult} signifying whether an error was
    *        encountered decoding stdout bytes
    */
   void onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult);
}
