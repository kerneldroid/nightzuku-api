package rikka.rish

import org.junit.Assert.assertEquals
import org.junit.Test

class RishTerminalTest {

    @Test
    fun `routable clears ATTY_IN and ATTY_ERR when ATTY_OUT is redirected`() {
        val inBit = RishConstants.ATTY_IN.toInt()
        val outBit = RishConstants.ATTY_OUT.toInt()
        val errBit = RishConstants.ATTY_ERR.toInt()

        // When stdout is NOT a TTY, all TTY bits must be cleared so pipes are used
        assertEquals(0.toByte(), RishTerminal.routable(0.toByte()))
        assertEquals(0.toByte(), RishTerminal.routable(inBit.toByte()))
        assertEquals(0.toByte(), RishTerminal.routable(errBit.toByte()))
        assertEquals(0.toByte(), RishTerminal.routable((inBit or errBit).toByte()))

        // When stdout IS a TTY, original TTY mask is preserved
        assertEquals(outBit.toByte(), RishTerminal.routable(outBit.toByte()))
        assertEquals((inBit or outBit).toByte(), RishTerminal.routable((inBit or outBit).toByte()))
        assertEquals((outBit or errBit).toByte(), RishTerminal.routable((outBit or errBit).toByte()))
        assertEquals((inBit or outBit or errBit).toByte(), RishTerminal.routable((inBit or outBit or errBit).toByte()))
    }
}
