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

package com.zaxxer.nuprocess.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import sun.misc.Unsafe;

@SuppressWarnings("restriction")
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
            return (ByteBuffer) DIRECT_BYTEBUFFER_CONSTRUCTOR.newInstance(new Object[] { address, capacity });
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
