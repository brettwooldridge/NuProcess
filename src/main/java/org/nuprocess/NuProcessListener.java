package org.nuprocess;

import java.nio.ByteBuffer;

/**
 * @author Brett Wooldridge
 */
public interface NuProcessListener
{
	/**
	 * This method is invoked when you call the {@link ProcessBuilder#start()}
	 * method.  This is your opportunity to store away the {@code NuProcess}
	 * instance, possibly in your listener, so that it can be used for
	 * interaction within other callbacks in this interface.
	 *
	 * @param nuProcess the @{link org.nuprocess.NuProcess} that is starting
	 */
	void onStart(NuProcess nuProcess);

    /**
     * This method is invoked when the process exits.
     *
     * @param exitCode the exit code of the process
     */
    void onExit(int exitCode);

	/**
     * This method is invoked when there is stdout data to process or an
     * the end-of-file (EOF) condition has been reached.  In the case
     * of EOF, the @{code buffer} parameter will be @{code null};
     * this is your signal that EOF has been reached.
     * <p>
     * You do not own the @{link java.lang.ByteBuffer ByteBuffer} provided to you.
     * You should not retain a reference to this buffer. <i>It is required that you
     * completely process the entire contents of the buffer when it is
     * passed to you</i>, as it's contents will be completely overwritten
     * immediately upon receipt of more data without regard for position
     * or limit.
     * <p>
     * Exceptions thrown out from your method will be ignored, but your
     * method should handle all exceptions itself.
     *
     * @param buffer a @{link java.lang.ByteBuffer ByteBuffer} containing received
     *               stderr data, or {@code null} signifying that an EOF condition
     *               has been reached
     */
    void onStdout(ByteBuffer buffer);

    /**
     * This method is invoked when there is stderr data to process or an
     * the end-of-file (EOF) condition has been reached.  In the case
     * of EOF, the @{code buffer} parameter will be @{code null};
     * this is your signal that EOF has been reached.
     * <p>
     * You do not own the @{link java.lang.ByteBuffer ByteBuffer} provided to you.
     * You should not retain a reference to this buffer. <i>It is required that you
     * completely process the entire contents of the buffer when it is
     * passed to you</i>, as it's contents will be completely overwritten
     * immediately upon receipt of more data without regard for position
     * or limit.
     * <p>
     * Users wishing to merge stderr into stdout should simply delegate
     * this callback to @{link #onStdout(buffer)} when invoked, like so:
     * <pre>
     *    public void onStderr(ByteBuffer buffer) {
     *       if (buffer != null) {
     *          onStdout(buffer);
     *       }
     *    }
     * </pre>
     * Notice that a null check is performed.  If you merge streams in
     * this way, and you do not check for null here, then your @{code onStdout()}
     * method will be called twice for an EOF condition; once when the
     * stdout stream closes, and once when the stderr stream closes.  If
     * you check for null as above, your @{link #onStdout()} method would only
     * be called once (for the close of stdout).
     * 
     * @param buffer a @{link java.lang.ByteBuffer ByteBuffer} containing received
     *               stderr data, or {@code null} signifying that an EOF condition
     *               has been reached
     */
    void onStderr(ByteBuffer buffer);

    /**
     * @param available the amount of available space in the pipe, do not attempt
     *                  to write more than this amount into stdin on your next
     *                  {@code NuProcess#write()} attempt
     * @return true if you have more data to write, false otherwise
     */
    boolean onStdinReady(int available);

    /**
     * This method is invoked when the process closes it's side of the stdin
     * pipe.
     */
    void onStdinClose();
}
