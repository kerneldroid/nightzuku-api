package rikka.shizuku.server.util

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * `Process.destroyForcibly()` and `Process.waitFor(timeout, unit)` were added in API 26,
 * while the server still supports API 25. Keep the fast path on newer devices and fall
 * back to the API 1 equivalents on API 25.
 */
internal object ProcessCompat {

    fun destroyForcibly(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    fun waitFor(process: Process, timeout: Long, unit: TimeUnit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeout, unit)
        }
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (hasExited(process)) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return hasExited(process)
            }
        }
        return hasExited(process)
    }

    private fun hasExited(process: Process): Boolean = try {
        process.exitValue()
        true
    } catch (_: IllegalThreadStateException) {
        false
    }
}
