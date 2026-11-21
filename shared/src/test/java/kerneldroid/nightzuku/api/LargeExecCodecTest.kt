package kerneldroid.nightzuku.api

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeExecCodecTest {

    @Test
    fun `encodeToBytes and decodeFromBytes round-trip multi-megabyte app_process command`() {
        val hugeArg = "X".repeat(2 * 1024 * 1024) // 2 MiB (> 1 MiB Binder transaction limit)
        val cmd = arrayOf(
            "/system/bin/app_process64",
            "-Djava.class.path=/data/app/client.apk",
            "/system/bin",
            "--nice-name=client:large",
            "com.example.Main",
            "--data=$hugeArg"
        )
        val env = arrayOf("FOO=bar", "CLASSPATH=/data/app/client.apk")
        val dir = "/data/local/tmp"

        assertTrue(LargeExecCodec.estimateCommandBytes(cmd, env, dir) > NightProtocol.EXEC_INLINE_PARCEL_THRESHOLD_BYTES)
        val bytes = LargeExecCodec.encodeToBytes(cmd, env, dir)
        val decoded = LargeExecCodec.decodeFromBytes(bytes)

        assertArrayEquals(cmd, decoded.cmd)
        assertArrayEquals(env, decoded.env)
        assertEquals(dir, decoded.dir)
    }

    @Test
    fun `encodeToBytes handles null env and null dir`() {
        val cmd = arrayOf("/system/bin/sh", "-c", "echo ok")
        val decoded = LargeExecCodec.decodeFromBytes(LargeExecCodec.encodeToBytes(cmd, null, null))
        assertArrayEquals(cmd, decoded.cmd)
        assertNull(decoded.env)
        assertNull(decoded.dir)
    }

    @Test(expected = IOException::class)
    fun `encodeToBytes rejects payloads exceeding EXEC_MAX_CMDLINE_BYTES`() {
        val oversized = "A".repeat(NightProtocol.EXEC_MAX_CMDLINE_BYTES + 1024)
        LargeExecCodec.encodeToBytes(arrayOf("/system/bin/sh", "-c", oversized), null, null)
    }
}
