package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The status poll asks for the serial every time; root is asked at most until it answers. */
class HardwareSerialTest {
    private val commands = mutableListOf<String>()

    private fun reader(vararg replies: String?): HardwareSerial {
        val queue = ArrayDeque(replies.toList())
        return HardwareSerial { command -> commands += command; queue.removeFirst() }
    }

    @Test fun aPanelWithoutRootNeverStartsARootShell() {
        val serial = reader()
        assertNull(serial.read(rootReady = false))
        assertEquals(emptyList<String>(), commands)
    }

    @Test fun rootIsAskedOnceAndItsAnswerKept() {
        val serial = reader("G000000000000000001\n")
        repeat(3) { assertEquals("G000000000000000001", serial.read(rootReady = true)) }
        assertEquals(listOf(HardwareSerial.COMMAND), commands)
    }

    @Test fun anEmptyAnswerIsAnAnswer() {
        val serial = reader("\n")
        repeat(2) { assertNull(serial.read(rootReady = true)) }
        assertEquals(1, commands.size)
    }

    @Test fun aFailedRootShellIsTriedAgainLater() {
        val serial = reader(null, "2600000000003")
        assertNull(serial.read(rootReady = true))
        assertEquals("2600000000003", serial.read(rootReady = true))
        assertEquals(2, commands.size)
    }
}
