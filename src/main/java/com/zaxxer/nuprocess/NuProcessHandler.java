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
	 * method.  This is your opportunity to store away the {@code NuProcess}
	 * instance, possibly in your listener, so that it can be used for
	 * interaction within other callbacks in this interface.
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
     * of EOF, the {@code buffer} parameter will be {@code null};
     * this is your signal that EOF has been reached.
     * <p>
     * You do not own the {@link ByteBuffer} provided to you.
     * You should not retain a reference to this buffer. <i>It is required that you
     * completely process the entire contents of the buffer when it is
     * passed to you</i>, as it's contents will be completely overwritten
     * immediately upon receipt of more data without regard for position
     * or limit.
     * <p>
     * Exceptions thrown out from your method will be ignored, but your
     * method should handle all exceptions itself.
     *
     * @param buffer a {@link ByteBuffer} containing received
     *               stderr data, or {@code null} signifying that an EOF condition
     *               has been reached
     */
    void onStdout(ByteBuffer buffer);

    /**
     * This method is invoked when there is stderr data to process or an
     * the end-of-file (EOF) condition has been reached.  In the case
     * of EOF, the {@code buffer} parameter will be {@code null};
     * this is your signal that EOF has been reached.
     * <p>
     * You do not own the {@link ByteBuffer} provided to you.
     * You should not retain a reference to this buffer. <i>It is required that you
     * completely process the entire contents of the buffer when it is
     * passed to you</i>, as it's contents will be completely overwritten
     * immediately upon receipt of more data without regard for position
     * or limit.
     * <p>
     * Users wishing to merge stderr into stdout should simply delegate
     * this callback to {@link #onStdout(ByteBuffer)} when invoked, like so:
     * <pre>
     *    public void onStderr(ByteBuffer buffer) {
     *       if (buffer != null) {
     *          onStdout(buffer);
     *       }
     *    }
     * </pre>
     * Notice that a null check is performed.  If you merge streams in
     * this way, and you do not check for null here, then your {@link #onStdout(ByteBuffer)}
     * method will be called twice for an EOF condition; once when the
     * stdout stream closes, and once when the stderr stream closes.  If
     * you check for null as above, your {@link #onStdout(ByteBuffer)} method would only
     * be called once (for the close of stdout).
     * 
     * @param buffer a {@link ByteBuffer} containing received
     *               stderr data, or {@code null} signifying that an EOF condition
     *               has been reached
     */
    void onStderr(ByteBuffer buffer);

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
