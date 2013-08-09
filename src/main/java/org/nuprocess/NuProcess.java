package org.nuprocess;


/**
 * @author Brett Wooldridge
 */
public interface NuProcess
{
    int BUFFER_CAPACITY = 65536;

    NuProcess start();

    int waitFor() throws InterruptedException;

    void wantWrite();

    void stdinClose();
   
    void destroy();
}
