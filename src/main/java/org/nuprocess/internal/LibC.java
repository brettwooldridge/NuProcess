package org.nuprocess.internal;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

public class LibC
{
    static
    {
        Native.register("c");
    }

    public static native int pipe(int[] fildes);

    public static native int fcntl(int fildes, int cmd);

    public static native int fcntl(int fildes, int cmd, long argO);

    public static native int close(int fildes);

    public static native int write(int fildes, Pointer buf, int nbyte);

    public static native int read(int fildes, Pointer buf, int nbyte);

    public static native int kill(int pid, int sig);

    public static native int waitpid(int pid, IntByReference status, int options);

    public static native int posix_spawnattr_init(PointerByReference posix_spawnattr_t);

    public static native int posix_spawnattr_destroy(PointerByReference posix_spawnattr_t);

    public static native int posix_spawnattr_setflags(PointerByReference posix_spawnattr_t, short flags);

    public static native int posix_spawn_file_actions_init(PointerByReference posix_spawn_file_actions_t);

    public static native int posix_spawn_file_actions_destroy(PointerByReference posix_spawn_file_actions_t);

    public static native int posix_spawn_file_actions_addclose(PointerByReference actions, int filedes);

    public static native int posix_spawn_file_actions_adddup2(PointerByReference actions, int fildes, int newfildes);

    public static native int posix_spawn_file_actions_addinherit_np(PointerByReference actions, int filedes);

    public static native int posix_spawn(IntByReference restrict_pid, String restrict_path, PointerByReference file_actions,
                                         PointerByReference /*const posix_spawnattr_t*/ restrict_attrp, StringArray /*String[]*/ argv,
                                         Pointer /*String[]*/ envp);

    // from /usr/include/sys/wait.h
    public static final int WNOHANG =0x00000001;
    
    // from /usr/include/sys/spawn.h
    public static final short POSIX_SPAWN_START_SUSPENDED = 0x0080;
    public static final short POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000;

    // From /usr/include/sys/signal.h
    public static final int SIGTERM = 15;
    public static final int SIGCONT = 19;
}
