package org.nuprocess;


/**
 * @author Brett Wooldridge
 */
public interface NuProcess
{
    int BUFFER_CAPACITY = 65536;

    void stdinClose();
   
    void destroy();
}
