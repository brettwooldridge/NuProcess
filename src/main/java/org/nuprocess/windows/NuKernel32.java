package org.nuprocess.windows;

import java.nio.Buffer;
import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

public interface NuKernel32 extends Kernel32
{
    /** The instance. */
    NuKernel32 INSTANCE = (NuKernel32) Native.loadLibrary("kernel32", NuKernel32.class, W32APIOptions.UNICODE_OPTIONS);

    /**
     * Creates an input/output (I/O) completion port and associates it with a
     * specified file handle, or creates an I/O completion port that is not yet
     * associated with a file handle, allowing association at a later time.
     *
     * @param FileHandle
     *            An open file handle or INVALID_HANDLE_VALUE.
     * @param ExistingCompletionPort
     *            A handle to an existing I/O completion port or NULL.
     * @param CompletionKey
     *            The per-handle user-defined completion key that is included in
     *            every I/O completion packet for the specified file handle.
     * @param NumberOfConcurrentThreads
     *            The maximum number of threads that the operating system can
     *            allow to concurrently process I/O completion packets for the
     *            I/O completion port.
     * @return If the function succeeds, the return value is the handle to an
     *         I/O completion port: If the ExistingCompletionPort parameter was
     *         NULL, the return value is a new handle. If the
     *         ExistingCompletionPort parameter was a valid I/O completion port
     *         handle, the return value is that same handle. If the FileHandle
     *         parameter was a valid handle, that file handle is now associated
     *         with the returned I/O completion port. If the function fails, the
     *         return value is NULL. To get extended error information, call the
     *         GetLastError function.
     */
    HANDLE CreateIoCompletionPort(HANDLE fileHandle, HANDLE existingCompletionPort, ULONG_PTR completionKey, int numberOfThreads);

    /**
     * Attempts to dequeue an I/O completion packet from the specified I/O
     * completion port. If there is no completion packet queued, the function
     * waits for a pending I/O operation associated with the completion port to
     * complete.
     *
     * @param CompletionPort
     *            A handle to the completion port.
     * @param lpNumberOfBytes
     *            A pointer to a variable that receives the number of bytes
     *            transferred during an I/O operation that has completed.
     * @param lpCompletionKey
     *            A pointer to a variable that receives the completion key value
     *            associated with the file handle whose I/O operation has
     *            completed.
     * @param lpOverlapped
     *            A pointer to a variable that receives the address of the
     *            OVERLAPPED structure that was specified when the completed I/O
     *            operation was started.
     * @param dwMilliseconds
     *            The number of milliseconds that the caller is willing to wait
     *            for a completion packet to appear at the completion port.
     * @return Returns nonzero (TRUE) if successful or zero (FALSE) otherwise.
     */
//    boolean GetQueuedCompletionStatus(HANDLE completionPort, IntByReference numberOfBytes, 
//                                      ULONG_PTRByReference completionKey, OVERLAPPED.ByReference lpOverlapped,
//                                      int dwMilliseconds);

    HANDLE CreateNamedPipe(String name, int dwOpenMode, int dwPipeMode, int nMaxInstances,
    		int nOutBufferSize, int nInBufferSize, int nDefaultTimeOut, SECURITY_ATTRIBUTES securityAttributes);

    boolean ConnectNamedPipe(HANDLE hNamedPipe, OVERLAPPED lpo);

    boolean HasOverlappedIoCompleted(OVERLAPPED lpo);

    DWORD ResumeThread(HANDLE hThread);
    
    boolean GetExitCodeProcess(HANDLE hProcess, IntByReference exitCode);

    /**
     * Reads data from the specified file or input/output (I/O) device. Reads
     * occur at the position specified by the file pointer if supported by the
     * device.
     *
     * This function is designed for both synchronous and asynchronous
     * operations. For a similar function designed solely for asynchronous
     * operation, see ReadFileEx
     *
     * @param hFile
     *            A handle to the device (for example, a file, file stream,
     *            physical disk, volume, console buffer, tape drive, socket,
     *            communications resource, mailslot, or pipe).
     * @param lpBuffer
     *            A pointer to the buffer that receives the data read from a
     *            file or device.
     * @param nNumberOfBytesToRead
     *            The maximum number of bytes to be read.
     * @param lpNumberOfBytesRead
     *            A pointer to the variable that receives the number of bytes
     *            read when using a synchronous hFile parameter
     * @param lpOverlapped
     *            A pointer to an OVERLAPPED structure is required if the hFile
     *            parameter was opened with FILE_FLAG_OVERLAPPED, otherwise it
     *            can be NULL.
     * @return If the function succeeds, the return value is nonzero (TRUE). If
     *         the function fails, or is completing asynchronously, the return
     *         value is zero (FALSE). To get extended error information, call
     *         the GetLastError function.
     *
     *         Note The GetLastError code ERROR_IO_PENDING is not a failure; it
     *         designates the read operation is pending completion
     *         asynchronously. For more information, see Remarks.
     */
    boolean ReadFile(HANDLE hFile, Buffer lpBuffer, int nNumberOfBytesToRead,
                     IntByReference lpNumberOfBytesRead, NuKernel32.OVERLAPPED lpOverlapped);

    /**
     * Writes data to the specified file or input/output (I/O) device.
     *
     * @param hFile
     *            A handle to the file or I/O device (for example, a file, file
     *            stream, physical disk, volume, console buffer, tape drive,
     *            socket, communications resource, mailslot, or pipe).
     * @param lpBuffer
     *            A pointer to the buffer containing the data to be written to
     *            the file or device.
     * @param nNumberOfBytesToWrite
     *            The number of bytes to be written to the file or device.
     * @param lpNumberOfBytesWritten
     *            A pointer to the variable that receives the number of bytes
     *            written when using a synchronous hFile parameter.
     * @param lpOverlapped
     *            A pointer to an OVERLAPPED structure is required if the hFile
     *            parameter was opened with FILE_FLAG_OVERLAPPED, otherwise this
     *            parameter can be NULL.
     * @return If the function succeeds, the return value is nonzero (TRUE). If
     *         the function fails, or is completing asynchronously, the return
     *         value is zero (FALSE). To get extended error information, call
     *         the GetLastError function.
     */
    boolean WriteFile(HANDLE hFile,Buffer lpBuffer, int nNumberOfBytesToWrite,
                      IntByReference lpNumberOfBytesWritten, NuKernel32.OVERLAPPED lpOverlapped);

    /**
     * The OVERLAPPED structure contains information used in 
     * asynchronous (or overlapped) input and output (I/O).
     */
    public static class OVERLAPPED extends Structure {
        public static class ByReference extends OVERLAPPED implements Structure.ByReference { }

        public ULONG_PTR Internal;
        public ULONG_PTR InternalHigh;
        public int Offset;
        public int OffsetHigh;
        public HANDLE hEvent;
        
        public OVERLAPPED()
        {
            super();
        }

        public OVERLAPPED(Pointer p)
        {
            super(p);
        }

        @SuppressWarnings("rawtypes")
        protected List getFieldOrder() {
            return Arrays.asList(new String[] { "Internal", "InternalHigh", "Offset", "OffsetHigh", "hEvent" });
        }
    }        

    int PIPE_ACCESS_DUPLEX = 0x00000003;
    int PIPE_ACCESS_INBOUND = 0x00000002;
    int PIPE_ACCESS_OUTBOUND = 0x00000001;

    int FILE_FLAG_OVERLAPPED = 0x40000000;
}
