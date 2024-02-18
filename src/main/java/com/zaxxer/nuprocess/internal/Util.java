package com.zaxxer.nuprocess.internal;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;

public class Util {

    public static void close(PointerType pointerType) {
        if (pointerType == null) {
            return;
        }
        close(pointerType.getPointer());
    }

    public static void close(Pointer p) {
        if (p instanceof Memory) {
            ((Memory) p).close();
        }
    }

}
