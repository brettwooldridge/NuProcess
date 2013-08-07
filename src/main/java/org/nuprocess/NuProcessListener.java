package org.nuprocess;

import java.nio.ByteBuffer;

public interface NuProcessListener
{
    /**
     * This method is invoked when there is stdout data to process or an
     * the end-of-file (EOF) condition has been reached.  In the case
     * of EOF, the <code>buffer</code> parameter will be <code>null</code>;
     * this is your signal that EOF has been reached.
     * <p>
     * You do not own the ByteBuffer provided to you.  You should not
     * retain a reference to this buffer.  <i>It is required that you
     * completely process all of the contents of the buffer when it is
     * passed to you</i>, as it's contents will be overwritten immediately
     * upon receipt of more data.
     * <p>
     * Exceptions thrown out from your method will be ignored, but your
     * method should handle all exceptions itself.
     *
     * @param buffer a ByteBuffer containing received stdout data, or null
     *               signifying that an EOF condition has been reached
     */
    void onStdout(ByteBuffer buffer);

    /**
     * This method is invoked when there is stderr data to process or an
     * the end-of-file (EOF) condition has been reached.  In the case
     * of EOF, the <code>buffer</code> parameter will be <code>null</code>;
     * this is your signal that EOF has been reached.
     * <p>
     * You do not own the ByteBuffer provided to you.  You should not
     * retain a reference to this buffer.  <i>It is required that you
     * completely process all of the contents of the buffer when it is
     * passed to you</i>, as it's contents will be overwritten immediately
     * upon receipt of more data.
     *
     * User wishing to merge stderr into stdout should simply delegate
     * this callback to <code>onStdout(buffer)</code> when invoked, like so:
     * <pre>
     *    public void onStderr(ByteBuffer buffer) {
     *       if (buffer != null) {
     *          onStdout(buffer);
     *       }
     *    }
     * </pre>
     * Notice that a null check is performed.  If you merge streams in
     * this way, and you do not check for null here, then your onStdout()
     * method will be called twice for an EOF condition; once when the
     * stdout stream closes, and once when the stderr stream closes.  If
     * you check for null as above, your onStdout() method would only
     * be called once (for the close of stdout).
     * 
     * @param buffer a ByteBuffer containing received stderr data, or null
     *               signifying that an EOF condition has been reached
     */
    void onStderr(ByteBuffer buffer);

    /**
     * This method is invoked when the process exits.
     *
     * @param statusCode
     */
    void onExit(int statusCode);
}
