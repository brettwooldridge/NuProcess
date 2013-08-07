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
     * this callback to <code>onStdout(buffer)</code> when invoked.
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
