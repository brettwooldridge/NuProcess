package org.nuprocess;

import java.nio.ByteBuffer;

/**
 * Convenience base class for a NuProcessListener with default
 * methods that can be overridden.
 * 
 * @author Brett Wooldridge
 */
public abstract class NuAbstractProcessListener implements NuProcessListener
{
    @Override
    public void onStart(NuProcess nuProcess)
    {
    }

    @Override
    public void onExit(int statusCode)
    {
    }

    @Override
    public void onStdout(ByteBuffer buffer)
    {
    }

    @Override
    public void onStderr(ByteBuffer buffer)
    {
    }

    @Override
    public boolean onStdinReady(int available)
    {
    	return false;
    }

    @Override
    public void onStdinClose()
    {
    }
}
