package org.nuprocess.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import sun.misc.Unsafe;

public final class UnsafeHelper
{
    private static final Unsafe UNSAFE;
    private static Constructor<?> DIRECT_BYTEBUFFER_CONSTRUCTOR;
    private static long ADDRESS_FIELD_OFFSET;

    static
    {
        try
        {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to obtain reference to sun.misc.Unsafe", e);
        }

        try
        {
            Class<?> clazz = Class.forName("java.nio.DirectByteBuffer");
            DIRECT_BYTEBUFFER_CONSTRUCTOR = clazz.getDeclaredConstructor(long.class, int.class);
            DIRECT_BYTEBUFFER_CONSTRUCTOR.setAccessible(true);

            clazz = Class.forName("java.nio.Buffer");
            Field addressField = clazz.getDeclaredField("address");
            ADDRESS_FIELD_OFFSET = UNSAFE.objectFieldOffset(addressField);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to obtain reference to java.nio.DirectByteBuffer constructor", e);
        }
    }

    public static Unsafe getUnsafe()
    {
        return UNSAFE;
    }

    public static ByteBuffer wrapNativeMemory(long address, int capacity)
    {
        try
        {
            return (ByteBuffer) DIRECT_BYTEBUFFER_CONSTRUCTOR.newInstance(new Object[] { address, 65536 });
        }
        catch (Exception e)
        {
            throw new RuntimeException("Unable to create wrapping DirectByteBuffer");
        }
    }

    public static long getDirectByteBufferAddress(ByteBuffer byteBuffer)
    {
        return UNSAFE.getLong(byteBuffer, ADDRESS_FIELD_OFFSET);
    }
}
