package rikka.rish

import android.os.ParcelFileDescriptor
import android.util.Log
import java.nio.charset.StandardCharsets

class RishHost(
    private val args: Array<String>?,
    private val env: Array<String>?,
    private val dir: String?,
    private val tty: Byte,
    stdin: ParcelFileDescriptor?,
    stdout: ParcelFileDescriptor?,
    stderr: ParcelFileDescriptor?
) {

    private val stdinFd: Int = detachFd(stdin)
    private val stdoutFd: Int = detachFd(stdout)
    private val stderrFd: Int = detachFd(stderr)

    @Volatile
    var pid: Int = 0
        private set

    @Volatile
    private var ptmx: Int = 0

    @Volatile
    var exitCode: Int = Int.MAX_VALUE
        private set

    fun start() {
        Log.d(TAG, "start")

        val argBlock = createCBytesForStringArray(args)
        val envBlock = createCBytesForStringArray(env)
        val dirBlock = createCBytesForString(dir)

        val result = start(
            argBlock,
            args?.size ?: 0,
            envBlock,
            env?.size ?: -1,
            dirBlock,
            tty,
            stdinFd,
            stdoutFd,
            stderrFd
        )

        pid = result[0]
        ptmx = result[1]

        val waitThread = Thread({ exitCode = waitFor(pid) }, "rish-wait-$pid")
        waitThread.isDaemon = true
        waitThread.start()
    }

    fun setWindowSize(size: Long) {
        Log.d(TAG, "setWindowSize")
        setWindowSize(ptmx, size)
    }

    companion object {
        private const val TAG = "RishHost"

        private fun createCBytesForStringArray(array: Array<String>?): ByteArray? {
            if (array == null) return null

            val bytes = Array(array.size) { i -> array[i].toByteArray(StandardCharsets.UTF_8) }
            var count = bytes.size
            for (b in bytes) {
                count += b.size
            }
            val block = ByteArray(count)
            var offset = 0
            for (arg in bytes) {
                System.arraycopy(arg, 0, block, offset, arg.size)
                offset += arg.size + 1
            }
            return block
        }

        private fun createCBytesForString(s: String?): ByteArray? {
            if (s == null) return null
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            val result = ByteArray(bytes.size + 1)
            System.arraycopy(bytes, 0, result, 0, bytes.size)
            result[result.size - 1] = 0
            return result
        }

        private fun detachFd(pfd: ParcelFileDescriptor?): Int {
            return pfd?.detachFd() ?: -1
        }

        @JvmStatic
        private external fun start(
            argBlock: ByteArray?,
            argc: Int,
            envBlock: ByteArray?,
            envc: Int,
            dirBlock: ByteArray?,
            tty: Byte,
            stdin: Int,
            stdout: Int,
            stderr: Int
        ): IntArray

        @JvmStatic
        private external fun setWindowSize(ptmx: Int, size: Long)

        @JvmStatic
        private external fun waitFor(pid: Int): Int
    }
}
