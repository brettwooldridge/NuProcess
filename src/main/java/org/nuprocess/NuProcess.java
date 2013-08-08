package org.nuprocess;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Brett Wooldridge
 */
public interface NuProcess
{
    int BUFFER_CAPACITY = 65536;

    /**
     * Writes <code>b.length</code> bytes from the specified byte array to the
     * stdin pipe of the process. The general contract for <code>write(b)</code>
     * is that it should have exactly the same effect as the call
     * <code>write(b, 0, b.length)</code>.
     *
     * @param      b   the data.
     * @return     how many bytes were written
     * @exception  IOException  if an I/O error occurs.
     */
    int write(byte[] buf) throws IOException;

    int write(byte[] buf, int off, int len) throws IOException;

    int write(ByteBuffer buf) throws IOException;

    void stdinClose();
}
