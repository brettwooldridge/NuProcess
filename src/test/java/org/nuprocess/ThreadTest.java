package org.nuprocess;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import junit.framework.Assert;

import org.junit.Test;
import org.nuprocess.osx.LibC;
import org.nuprocess.osx.PThread.ByValue;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

public class ThreadTest
{
    @Test
    public void testPThreadSelf() throws Exception
    {
        final AtomicLong pointer = new AtomicLong();

        Runnable runnable = new Runnable() {
            public void run()
            {
                LibC libc = LibC.INSTANCE;
                ByValue self = libc.pthread_self();
                pointer.set(Pointer.nativeValue(self.getPointer()));
            }
        };

        Thread t = new Thread(runnable);
        t.start();
        t.join();

        Assert.assertFalse(pointer.get() == 0);
    }

    @Test
    public void testPosix_spawn_file_actions_init()
    {
        LibC libc = LibC.INSTANCE;

        Pointer posix_spawn_file_actions = new Memory(Pointer.SIZE);
        // posix_spawn_file_actions_t.setPointer(0, new Memory(Pointer.SIZE));

        libc.posix_spawn_file_actions_init(posix_spawn_file_actions);

        libc.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
    }

    @Test
    public void testSpawn()
    {
        LibC libc = LibC.INSTANCE;

        int[] out = new int[2];
        int[] in = new int[2];
        String[] args = {"/bin/cat"};

        libc.pipe(out);
        libc.pipe(in);

        Pointer posix_spawn_file_actions = new Memory(Pointer.SIZE);
        // posix_spawn_file_actions.setPointer(0, new Memory(Pointer.SIZE));

        int rc = libc.posix_spawn_file_actions_init(posix_spawn_file_actions);
        Assert.assertEquals(0, rc);
        rc = libc.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, out[0], 0);
        Assert.assertEquals(0, rc);
        rc = libc.posix_spawn_file_actions_addclose(posix_spawn_file_actions, out[1]);
        Assert.assertEquals(0, rc);

        rc = libc.posix_spawn_file_actions_adddup2(posix_spawn_file_actions, in[1], 1);
        Assert.assertEquals(0, rc);
        rc = libc.posix_spawn_file_actions_addclose(posix_spawn_file_actions, in[0]);
        Assert.assertEquals(0, rc);

        IntByReference restrict_pid = new IntByReference();
        rc = libc.posix_spawn(restrict_pid, args[0], posix_spawn_file_actions, Pointer.NULL, new StringArray(args), new StringArray(new String[0]));
        Assert.assertEquals(0, rc);

        libc.close(out[0]);
        libc.close(in[1]);

        ByteBuffer buf = ByteBuffer.allocateDirect(1024);
        buf.asCharBuffer().put("Hello world!");
        libc.write(out[1], buf, 12);
        libc.close(out[1]);

        rc = libc.read(in[0], buf, 1024);
        Assert.assertTrue(rc > 0);
        char[] dst = new char[12];
        buf.asCharBuffer().get(dst);
        Assert.assertEquals("Hello world!", new String(dst));

        IntByReference status = new IntByReference();
        libc.wait(status);

        libc.posix_spawn_file_actions_destroy(posix_spawn_file_actions);
    }
}
