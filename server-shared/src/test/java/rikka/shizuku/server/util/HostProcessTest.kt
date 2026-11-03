package rikka.shizuku.server.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProcessTest {

    @Test
    fun `capture and verify match current Linux process identity and reject mismatched PID or UID`() {
        val currentPid = ProcessHandle.current().pid().toInt()
        val currentUid = HostProcess.readUid(currentPid)
        val startTime = HostProcess.readStartTime(currentPid)

        assertTrue("startTime from /proc/<pid>/stat must be positive", startTime > 0L)
        assertTrue("uid from /proc/<pid>/status must be non-negative", currentUid >= 0)

        val host = HostProcess.capture(currentPid, currentUid)
        assertNotNull(host)
        assertEquals(currentPid, host!!.pid)
        assertEquals(currentUid, host.uid)
        assertEquals(startTime, host.startTime)

        // Matching startTime and UID verifies true
        assertTrue(host.verify(currentPid))

        // Mismatched UID must return null on capture and false on verify
        assertNull(HostProcess.capture(currentPid, currentUid + 99999))
        val wrongUidHost = HostProcess(currentPid, startTime, currentUid + 99999)
        assertFalse(wrongUidHost.verify(currentPid))

        // Mismatched startTime (simulating PID reuse) must verify false
        val reusedPidHost = HostProcess(currentPid, startTime + 1L, currentUid)
        assertFalse(reusedPidHost.verify(currentPid))
    }

    @Test
    fun `temp proc fixture parses stat and status files`() {
        val procRoot = Files.createTempDirectory("hostproc").toFile()
        try {
            val pid = 4242
            val dir = File(procRoot, pid.toString()).apply { mkdirs() }
            val fields = (0 until 18).map { "0" } + "98765"
            File(dir, "stat").writeText("$pid (host-process) S ${fields.joinToString(" ")}\n")
            File(dir, "status").writeText("Name:\thost-process\nUid:\t10123\t10123\t10123\t10123\n")

            assertEquals(98_765L, HostProcess.readStartTime(pid, procRoot))
            assertEquals(10_123, HostProcess.readUid(pid, procRoot))
            val host = HostProcess.capture(pid, procRoot)
            assertNotNull(host)
            assertEquals(pid, host!!.pid)
            assertEquals(98_765L, host.startTime)
            assertEquals(10_123, host.uid)
            assertTrue(host.verify(pid))
        } finally {
            procRoot.deleteRecursively()
        }
    }

    @Test
    fun `temp proc fixture treats malformed or missing files as unknown`() {
        val procRoot = Files.createTempDirectory("hostproc-bad").toFile()
        try {
            val pid = 5555
            val dir = File(procRoot, pid.toString()).apply { mkdirs() }
            File(dir, "stat").writeText("not a proc stat line")
            File(dir, "status").writeText("Name:\tbroken\n")

            assertEquals(0L, HostProcess.readStartTime(pid, procRoot))
            assertEquals(-1, HostProcess.readUid(pid, procRoot))
            assertNull(HostProcess.capture(pid, procRoot))
            assertNull(HostProcess.capture(pid + 1, procRoot))
            assertEquals(0L, HostProcess.readStartTime(-1, procRoot))
            assertEquals(-1, HostProcess.readUid(-1, procRoot))
        } finally {
            procRoot.deleteRecursively()
        }
    }
}
