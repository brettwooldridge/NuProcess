package com.zaxxer.nuprocess.internal;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

public class JnaHelper {

    public static void free(IntByReference pointer) {
        free((Memory) pointer.getPointer());
    }

    public static void free(Pointer pointer) {
        if (pointer instanceof Memory) {
            ((Memory) pointer).close();
        } else {
            throw new IllegalArgumentException("Expecting Pointer to be an instance of Memory");
        }
    }
    public static void free(Memory memory) {
        memory.close();
    }

}
