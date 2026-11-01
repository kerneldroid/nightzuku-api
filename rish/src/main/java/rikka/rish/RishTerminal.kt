package rikka.rish

import android.os.Parcel
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileDescriptor

class RishTerminal @Throws(ErrnoException::class, RemoteException::class) constructor(
    private val argv: Array<String>
) {

    private val tty: Byte = prepare()
    private var stdin: Array<FileDescriptor>? = null
    private var stdout: Array<FileDescriptor>? = null
    private var stderr: Array<FileDescriptor>? = null

    @Volatile
    private var ttyFd: Int = -1

    @Volatile
    var exitCode: Int = 0
        private set

    init {
        createHost()
    }

    @Throws(ErrnoException::class, RemoteException::class)
    private fun createHost() {
        Log.d(TAG, "createHost")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        val env = System.getenv().entries.map { "${it.key}=${it.value}" }.toTypedArray()
        val dir = File("").absolutePath

        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken()!!)
            data.writeByte(tty)
            val stdinPipe = Os.pipe()
            stdin = stdinPipe
            data.writeFileDescriptor(stdinPipe[0])
            val stdoutPipe = Os.pipe()
            stdout = stdoutPipe
            data.writeFileDescriptor(stdoutPipe[1])
            if ((tty.toInt() and RishConstants.ATTY_ERR) == 0) {
                val stderrPipe = Os.pipe()
                stderr = stderrPipe
                data.writeFileDescriptor(stderrPipe[1])
            }
            data.writeStringArray(argv)
            data.writeStringArray(env)
            data.writeString(dir)
            RishConfig.getBinder()!!.transact(
                RishConfig.getTransactionCode(RishConfig.TRANSACTION_createHost),
                data,
                reply,
                0
            )
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()

            closeFd(stdin, 0)
            closeFd(stdout, 1)
            closeFd(stderr, 1)
        }
    }

    fun start() {
        Log.d(TAG, "start")

        ttyFd = start(tty, getFd(stdin, 1), getFd(stdout, 0), getFd(stderr, 0))

        if (ttyFd != -1) {
            val winThread = Thread({
                while (true) {
                    Log.d(TAG, "waitForWindowSizeChange")
                    try {
                        val size = waitForWindowSizeChange(ttyFd)
                        setWindowSize(size)
                    } catch (e: Throwable) {
                        Log.w(TAG, Log.getStackTraceString(e))
                        break
                    }
                }
            }, "rish-winch")
            winThread.isDaemon = true
            winThread.start()
        }
    }

    @Throws(RemoteException::class)
    private fun setWindowSize(size: Long) {
        Log.d(TAG, "setWindowSize")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken()!!)
            data.writeLong(size)
            RishConfig.getBinder()!!.transact(
                RishConfig.getTransactionCode(RishConfig.TRANSACTION_setWindowSize),
                data,
                reply,
                0
            )
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Throws(RemoteException::class)
    private fun requestExitCode(): Int {
        Log.d(TAG, "requestExitCode")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RishConfig.getInterfaceToken()!!)
            RishConfig.getBinder()!!.transact(
                RishConfig.getTransactionCode(RishConfig.TRANSACTION_getExitCode),
                data,
                reply,
                0
            )
            reply.readException()
            reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun waitFor(): Int {
        Log.d(TAG, "waitFor")
        waitForProcessExit()
        try {
            exitCode = requestExitCode()
        } catch (e: Throwable) {
            Log.w(TAG, Log.getStackTraceString(e))
            exitCode = -1
        } finally {
            closeFd(stdin, 1)
            closeFd(stdout, 0)
            closeFd(stderr, 0)
        }
        return exitCode
    }

    companion object {
        private const val TAG = "RishTerminal"

        @JvmStatic
        fun getFd(fileDescriptor: Array<FileDescriptor>?, i: Int): Int {
            if (fileDescriptor == null) return -1
            return FileDescriptors.getFd(fileDescriptor[i])
        }

        @JvmStatic
        fun closeFd(fileDescriptor: Array<FileDescriptor>?, i: Int) {
            if (fileDescriptor == null) return
            FileDescriptors.closeSilently(fileDescriptor[i])
        }

        @JvmStatic
        private external fun prepare(): Byte

        @JvmStatic
        private external fun start(tty: Byte, stdin: Int, stdout: Int, stderr: Int): Int

        @JvmStatic
        private external fun waitForWindowSizeChange(fd: Int): Long

        @JvmStatic
        private external fun waitForProcessExit()
    }
}
