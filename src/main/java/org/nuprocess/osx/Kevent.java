package org.nuprocess.osx;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;

public class Kevent extends Structure
{
    public static class KeventPointer extends PointerType { };

    // uintptr_t       ident;          /* identifier for this event */
    public NativeLong ident;
    // int16_t        filter;         /* filter for event */
    public short filter;
    // uint16_t       flags;          /* general flags */
    public short flags;
    // uint32_t       fflags;         /* filter-specific flags */
    public int fflags;
    // intptr_t       data;           /* filter-specific data */
    public NativeLong data;
    // void           *udata;         /* opaque user data identifier */
    public Pointer udata;

    public Kevent()
    {
        super(ALIGN_DEFAULT);
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected List getFieldOrder()
    {
        return Arrays.asList("ident", "filter", "flags", "fflags", "data", "udata");
    }

    protected static Kevent EV_SET(Kevent kev, NativeLong ident, int filter, int flags, int fflags, NativeLong data, Pointer udata)
    {
        kev.ident = ident;
        kev.filter = (short) filter;
        kev.flags = (short) flags;
        kev.fflags = fflags;
        kev.data = data;
        kev.udata = udata;

        return kev;
    }

    /* actions */
    public static final int EV_ADD     = 0x0001;          /* add event to kq (implies enable) */
    public static final int EV_DELETE  = 0x0002;          /* delete event from kq */
    public static final int EV_ENABLE  = 0x0004;          /* enable event */
    public static final int EV_DISABLE = 0x0008;          /* disable event (not reported) */
    public static final int EV_RECEIPT = 0x0040;          /* force EV_ERROR on success, data == 0 */

    /* flags */
    public static final int EV_ONESHOT  = 0x0010;         /* only report one occurrence */
    public static final int EV_CLEAR    = 0x0020;         /* clear event state after reporting */
    public static final int EV_DISPATCH = 0x0080;         /* disable event after reporting */

    public static final int EV_SYSFLAGS = 0xF000;         /* reserved by system */
    public static final int EV_FLAG0    = 0x1000;         /* filter-specific flag */
    public static final int EV_FLAG1    = 0x2000;         /* filter-specific flag */

    /* returned values */
    public static final int EV_EOF        = 0x8000;         /* EOF detected */
    public static final int EV_ERROR    = 0x4000;         /* error, data contains errno */

    public static final int EVFILT_READ     = (-1);
    public static final int EVFILT_WRITE    = (-2);
    public static final int EVFILT_AIO      = (-3);    /* attached to aio requests */
    public static final int EVFILT_VNODE    = (-4);    /* attached to vnodes */
    public static final int EVFILT_PROC     = 65531; // (-5);    /* attached to struct proc */
    public static final int EVFILT_SIGNAL   = (-6);    /* attached to struct proc */
    public static final int EVFILT_TIMER    = (-7);    /* timers */
    public static final int EVFILT_MACHPORT = (-8);    /* Mach portsets */
    public static final int EVFILT_FS       = (-9);    /* Filesystem events */
    public static final int EVFILT_USER     = (-10);   /* User events */
                                             /* (-11) unused */
    public static final int EVFILT_VM       = (-12);   /* Virtual memory events */

    public static final int NOTE_EXIT        = 0x80000000;     /* process exited */
    public static final int NOTE_REAP        = 0x10000000;     /* process exited */    
    public static final int NOTE_EXITSTATUS  = 0x04000000;     /* exit status to be returned, valid for child process only */
}
