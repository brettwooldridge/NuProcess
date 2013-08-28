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

package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * @author Brett Wooldridge
 */
public class PThread extends Structure
{
    public static class ByValue extends PThread implements Structure.ByValue { };

    // long __sig;
    public NativeLong __sig;
    // struct __darwin_pthread_handler_rec *__cleanup_stack
    public Pointer __pdarwin_pthread_handler_rec;
    // char __opaque[__PTHREAD_SIZE__];
    public Pointer __opaque;
    
    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("__sig", "__pdarwin_pthread_handler_rec", "__opaque");
    }
}
