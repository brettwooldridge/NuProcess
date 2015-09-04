/*
 * Copyright (C) 2013 Brett Wooldridge
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

/**
 * Executors of a {@link NuProcess} must provide an implementation of this class
 * to the {@link ProcessBuilder} prior to calling {@link ProcessBuilder#start()}.
 * This interface provides the primary means of asynchronous interaction between
 * the running child process and your code.
 * <p>
 * Methods specified by this interface will be invoked by the asynchronous 
 * processing thread.  It is up to the implementation of this interface to handle
 * the resulting state changes or process the data passed to it.
 * <p>
 * Note that the processing thread that executes these callbacks on behalf of a
 * NuProcess is the same thread that handles processing for other instances of
 * NuProcess, and as a result you should never perform a blocking or time-consuming
 * operation on the callback thread.  Doing so will block or severely hinder
 * the processing of other NuProcess instances.  Any operation that requires more
 * than single-digit millisecond processing should be handed off to another
 * thread so that the processing thread can return to immediately service other
 * NuProcess instances.
 * 
 * @author Brett Wooldridge
 */
public interface NuProcessHandler
{
   /**
    * This method is invoked when you call the {@link ProcessBuilder#start()}
    * method. This is your opportunity to store away the {@code NuProcess}
    * instance, possibly in your listener, so that it can be used for
    * interaction within other callbacks in this interface.
    * <p>
    * Unlike the {@link #onStart(NuProcess)} method, this method is invoked
    * before the process is spawned, and is guaranteed to be invoked before
    * any other methods are called.
    * 
    * @param nuProcess
    *    The {@link NuProcess} that is starting. Note that the instance is not
    *    yet initialized, so it is not legal to call any of its methods, and
    *    doing so will result in undefined behavior. If you need to call any
    *    of the instance's methods, use {@link #onStart(NuProcess)} instead.
    */
   void onPreStart(NuProcess nuProcess);

   /**
    * This method is invoked when you call the {@link ProcessBuilder#start()}
    * method.  This is your opportunity to store away the {@code NuProcess}
    * instance, possibly in your listener, so that it can be used for
    * interaction within other callbacks in this interface.
    * <p>
    * Note that this method is called at some point after the process is spawned.
    * It is possible for other methods (even {@link #onExit(int)}) to be called
    * first. If you need a guarantee that no other methods will be called first,
    * use {@link #onPreStart(NuProcess)} instead.
    *
    * @param nuProcess the {@link NuProcess} that is starting
    */
   void onStart(NuProcess nuProcess);

   /**
    * This method is invoked when the process exits.  This method is also invoked
    * immediately in the case of a failure to launch the child process.
    * <p>
    * There are two special values, besides ordinary process exit codes, that may
    * be passed to this method.  A value of {@link Integer#MIN_VALUE} indicates
    * some kind of launch failure.  A value of {@link Integer#MAX_VALUE} indicates
    * an unknown or expected failure mode.
    *
    * @param exitCode the exit code of the process, or a special value indicating
    *  unexpected failures
    */
   void onExit(int exitCode);

   /**
    * This method is invoked when there is stdout data to process or an
    * the end-of-file (EOF) condition has been reached.  In the case
    * of EOF, the {@code closed} parameter will be {@code true};
    * this is your signal that EOF has been reached.
    * <p>
    * You do not own the {@link ByteBuffer} provided to you.
    * You should not retain a reference to this buffer.
    * <p>
    * Upon returning from this method, if any bytes are left in the
    * buffer (i.e., {@code buffer.hasRemaining()} returns {@code true}),
    * then the buffer will be {@link ByteBuffer#compact() compacted}
    * after returning. Any unused data will be kept at the
    * start of the buffer and passed back to you as part of the next
    * invocation of this method (which might be when EOF is reached
    * and {@code closed} is {@code true}).
    * <p>
    * Exceptions thrown out from your method will be ignored, but your
    * method should handle all exceptions itself.
    *
    * @param buffer a {@link ByteBuffer} containing received
    *               stdout data
    * @param closed {@code true} if EOF has been reached
    */
   void onStdout(ByteBuffer buffer, boolean closed);

   /**
    * This method is invoked when there is stderr data to process or an
    * the end-of-file (EOF) condition has been reached.  In the case
    * of EOF, the {@code closed} parameter will be {@code true};
    * this is your signal that EOF has been reached.
    * <p>
    * You do not own the {@link ByteBuffer} provided to you.
    * You should not retain a reference to this buffer.
    * <p>
    * Upon returning from this method, if any bytes are left in the
    * buffer (i.e., {@code buffer.hasRemaining()} returns {@code true}),
    * then the buffer will be {@link ByteBuffer#compact() compacted}
    * after returning. Any unused data will be kept at the
    * start of the buffer and passed back to you as part of the next
    * invocation of this method (which might be when EOF is reached
    * and {@code closed} is {@code true}).
    * <p>
    * Users wishing to merge stderr into stdout should simply delegate
    * this callback to {@link #onStdout(ByteBuffer, boolean)} when invoked, like so:
    * <pre>
    *    public void onStderr(ByteBuffer buffer, closed) {
    *       if (!closed) {
    *          onStdout(buffer, closed);
    *       }
    *    }
    * </pre>
    * <p>
    * Notice that an EOF check is performed.  If you merge streams in
    * this way, and you do not check for EOF here, then your
    * {@link #onStdout(ByteBuffer, boolean)}
    * method will be called twice for an EOF condition; once when the
    * stdout stream closes, and once when the stderr stream closes.  If
    * you check for EOF as above, your {@link #onStdout(ByteBuffer, boolean)}
    * method would only be called once (for the close of stdout).
    * <p>
    * Exceptions thrown out from your method will be ignored, but your
    * method should handle all exceptions itself.
    * 
    * @param buffer a {@link ByteBuffer} containing received
    *               stderr data
    * @param closed {@code true} if EOF has been reached
    */
  void onStderr(ByteBuffer buffer, boolean closed);

   /**
    * This method is invoked after you have expressed a desire to write to stdin
    * by first calling {@link NuProcess#wantWrite()}.  When this method is invoked,
    * your code should write data to be sent to the stdin of the child process into
    * the provided {@link ByteBuffer}.  After writing data into the {@code buffer}
    * your code <em>must</em> {@link ByteBuffer#flip() flip} the buffer before
    * returning.
    * <p>
    * If not all of the data needed to be written will fit in the provided {@code buffer},
    * this method can return {@code true} to indicate a desire to write more data.  If
    * there is no more data to be written at the time this method is invoked, then
    * {@code false} should be returned from this method.  It is always possible to
    * call {@link NuProcess#wantWrite()} later if data becomes available to be written.
    *
    * @param buffer a {@link ByteBuffer} into which your stdin-bound data should be written 
    * @return true if you have more data to write immediately, false otherwise
    */
   boolean onStdinReady(ByteBuffer buffer);
}
