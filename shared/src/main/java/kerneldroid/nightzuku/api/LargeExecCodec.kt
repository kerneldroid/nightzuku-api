package kerneldroid.nightzuku.api

import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Out-of-band pipe/FD codec for transmitting multi-megabyte command vectors (`cmd`, `env`, `dir`)
 * across Binder IPC without hitting Android's 1 MiB Binder transaction buffer limit
 * (`TransactionTooLargeException`).
 */
object LargeExecCodec {

    private const val MAGIC = 0x4E5A4558 // "NZEX"
    private const val VERSION = 1
    private const val MAX_ARRAY_ITEMS = 65_536

    private val pipeWriterExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "night-fd-cmd-writer").apply { isDaemon = true }
    }

    data class DecodedExecRequest(
        val cmd: Array<String>,
        val env: Array<String>?,
        val dir: String?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedExecRequest) return false
            return cmd.contentEquals(other.cmd) &&
                (env == null && other.env == null || (env != null && other.env != null && env.contentEquals(other.env))) &&
                dir == other.dir
        }

        override fun hashCode(): Int {
            var result = cmd.contentHashCode()
            result = 31 * result + (env?.contentHashCode() ?: 0)
            result = 31 * result + (dir?.hashCode() ?: 0)
            return result
        }
    }

    @JvmStatic
    fun estimateCommandBytes(cmd: Array<String>, env: Array<String>?, dir: String?): Int {
        var total = 24
        for (s in cmd) {
            total += 4 + s.toByteArray(StandardCharsets.UTF_8).size
        }
        if (env != null) {
            for (s in env) {
                total += 4 + s.toByteArray(StandardCharsets.UTF_8).size
            }
        }
        if (dir != null) {
            total += 4 + dir.toByteArray(StandardCharsets.UTF_8).size
        }
        return total
    }

    @JvmStatic
    @Throws(IOException::class)
    fun encodeToBytes(cmd: Array<String>, env: Array<String>?, dir: String?): ByteArray {
        if (cmd.isEmpty()) {
            throw IOException("Command array must not be empty")
        }
        if (cmd.size > MAX_ARRAY_ITEMS || (env != null && env.size > MAX_ARRAY_ITEMS)) {
            throw IOException("Command/env array exceeds maximum item count ($MAX_ARRAY_ITEMS)")
        }
        val estimated = estimateCommandBytes(cmd, env, dir)
        if (estimated > NightProtocol.EXEC_MAX_CMDLINE_BYTES) {
            throw IOException(
                "Command payload ($estimated bytes) exceeds maximum (${NightProtocol.EXEC_MAX_CMDLINE_BYTES} bytes)"
            )
        }

        val baos = ByteArrayOutputStream(estimated.coerceAtLeast(256))
        DataOutputStream(baos).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            writeStringArray(out, cmd)
            if (env != null) {
                out.writeByte(1)
                writeStringArray(out, env)
            } else {
                out.writeByte(0)
            }
            if (dir != null) {
                out.writeByte(1)
                val dirBytes = dir.toByteArray(StandardCharsets.UTF_8)
                out.writeInt(dirBytes.size)
                out.write(dirBytes)
            } else {
                out.writeByte(0)
            }
            out.flush()
        }
        val bytes = baos.toByteArray()
        if (bytes.size > NightProtocol.EXEC_MAX_CMDLINE_BYTES) {
            throw IOException(
                "Encoded command payload (${bytes.size} bytes) exceeds maximum (${NightProtocol.EXEC_MAX_CMDLINE_BYTES} bytes)"
            )
        }
        return bytes
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decodeFromBytes(bytes: ByteArray): DecodedExecRequest {
        if (bytes.size > NightProtocol.EXEC_MAX_CMDLINE_BYTES) {
            throw IOException("Command payload exceeds maximum (${NightProtocol.EXEC_MAX_CMDLINE_BYTES} bytes)")
        }
        return decodeFromStream(ByteArrayInputStream(bytes))
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decodeFromStream(stream: InputStream): DecodedExecRequest {
        val input = DataInputStream(BufferedInputStream(stream))
        val magic = input.readInt()
        if (magic != MAGIC) {
            throw IOException("Invalid command payload magic: 0x${Integer.toHexString(magic)}")
        }
        val version = input.readInt()
        if (version != VERSION) {
            throw IOException("Unsupported command payload version: $version")
        }
        val budget = intArrayOf(NightProtocol.EXEC_MAX_CMDLINE_BYTES)
        val cmd = readStringArray(input, budget)
        if (cmd.isEmpty()) {
            throw IOException("Command array must not be empty")
        }
        val hasEnv = input.readByte().toInt() != 0
        val env = if (hasEnv) readStringArray(input, budget) else null
        val hasDir = input.readByte().toInt() != 0
        val dir = if (hasDir) readUtf8String(input, budget) else null
        return DecodedExecRequest(cmd, env, dir)
    }

    /**
     * Streams the encoded command payload over an OS pipe (`ParcelFileDescriptor.createPipe()`)
     * using a background writer thread so callers never deadlock on payloads exceeding the 64 KiB
     * kernel pipe buffer, including over `IBinder.FLAG_ONEWAY` calls.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeToPipe(cmd: Array<String>, env: Array<String>?, dir: String?): ParcelFileDescriptor {
        val encoded = encodeToBytes(cmd, env, dir)
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        pipeWriterExecutor.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    out.write(encoded)
                    out.flush()
                }
            } catch (_: Throwable) {
                runCatching { writeEnd.close() }
            }
        }
        return readEnd
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decodeFromParcelFileDescriptor(pfd: ParcelFileDescriptor): DecodedExecRequest {
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            return decodeFromStream(input)
        }
    }

    @Throws(IOException::class)
    private fun writeStringArray(out: DataOutputStream, values: Array<String>) {
        out.writeInt(values.size)
        for (value in values) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }

    @Throws(IOException::class)
    private fun readStringArray(input: DataInputStream, budget: IntArray): Array<String> {
        val count = input.readInt()
        if (count < 0 || count > MAX_ARRAY_ITEMS) {
            throw IOException("Invalid string array count: $count")
        }
        return Array(count) { readUtf8String(input, budget) }
    }

    @Throws(IOException::class)
    private fun readUtf8String(input: DataInputStream, budget: IntArray): String {
        val len = input.readInt()
        if (len < 0 || len > budget[0]) {
            throw IOException("Invalid string length $len (remaining budget=${budget[0]})")
        }
        budget[0] -= len
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
