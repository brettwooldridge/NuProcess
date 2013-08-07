package org.nuprocess.osx;

import java.nio.Buffer;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;

public interface LibC extends Library
{
    LibC INSTANCE = (LibC) Native.loadLibrary("c", LibC.class);

    int pipe(int[] fildes);

    int fcntl(int fildes, int cmd, Pointer args);

    int close(int fildes);

    int write(int fildes, Buffer buf, int nbyte);

    int read(int fildes, Buffer buf, int nbyte);

    int kill(int pid, int sig);

    int wait(IntByReference status);

    PThread.ByValue pthread_self();

    int posix_spawnattr_init(Pointer posix_spawnattr_t);

    int posix_spawnattr_destroy(Pointer posix_spawnattr_t);

    int posix_spawnattr_setflags(Pointer posix_spawnattr_t, short flags);

    int posix_spawn_file_actions_init(Pointer posix_spawn_file_actions_t);

    int posix_spawn_file_actions_destroy(Pointer posix_spawn_file_actions_t);

    int posix_spawn_file_actions_addclose(Pointer actions, int filedes);

    int posix_spawn_file_actions_adddup2(Pointer actions, int fildes, int newfildes);

    int posix_spawn(IntByReference restrict_pid, String restrict_path, Pointer file_actions,
                    Pointer /*const posix_spawnattr_t*/ restrict_attrp, StringArray /*String[]*/ argv, Pointer /*String[]*/ envp);

    int kqueue();

    int kevent(int kq, Kevent[] changeList, int nchanges, Kevent[] eventList, int nevents, Pointer timespec);

    int O_NONBLOCK = 0x0004;

    // from /usr/include/sys/spawn.h
    short POSIX_SPAWN_START_SUSPENDED = 0x0080;
    short POSIX_SPAWN_CLOEXEC_DEFAULT = 0x4000;

    // From /usr/include/sys/signal.h
    int SIGCONT = 19;
}
