package rikka.shizuku.server.util

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
}
