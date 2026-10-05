package io.panelassistant.android.sensors

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Vi530xRangeSessionTest {
    @Test fun requestsOneReadingAndDeliversItsRange() {
        val commands = ByteArrayOutputStream()
        val values = mutableListOf<Float>()

        val alive = Vi530xRangeSession.poll(
            ByteArrayInputStream("D=1234 S=0 C=900\n".toByteArray()),
            commands,
            values::add,
        )

        assertEquals("VI530X\n", commands.toString())
        assertEquals(listOf(1234f), values)
        assertEquals(true, alive)
    }

    @Test fun aClosedConnectionIsReportedRatherThanLoopingOnNothing() {
        val values = mutableListOf<Float>()
        val alive = Vi530xRangeSession.poll(
            ByteArrayInputStream(ByteArray(0)),
            ByteArrayOutputStream(),
            values::add,
        )
        assertEquals(emptyList<Float>(), values)
        assertEquals(false, alive)
    }

    @Test fun aHelperThatCannotReadTheSensorDeliversNoValueButKeepsTheConnection() {
        // ERR is the helper saying "no measurement", which is different from the socket dying. The
        // connection stays usable so the next poll can succeed once the sensor answers again.
        val values = mutableListOf<Float>()
        var unavailable = 0
        val alive = Vi530xRangeSession.poll(
            ByteArrayInputStream("ERR\n".toByteArray()),
            ByteArrayOutputStream(),
            values::add,
        ) { unavailable++ }
        assertEquals(emptyList<Float>(), values)
        assertEquals(1, unavailable)
        assertEquals(true, alive)
    }

    @Test fun aMalformedReplyIsNotTreatedAsAHealthySensor() {
        var unavailable = 0
        val alive = Vi530xRangeSession.poll(
            ByteArrayInputStream("unexpected\n".toByteArray()),
            ByteArrayOutputStream(),
            {},
        ) { unavailable++ }

        assertEquals(false, alive)
        assertEquals(0, unavailable)
    }

    @Test fun negativeRangesSurviveBecauseTheDriverSignsThem() {
        // RangeTof is int16_t in the vendor ABI. A negative reading is the driver telling us something,
        // and swallowing it here would hide exactly the evidence the first hardware run needs.
        assertEquals(-1f, Vi530xRangeSession.parse("D=-1 S=13 C=0"))
    }

    @Test fun malformedOrPartialRepliesAreNotGuessedAt() {
        assertNull(Vi530xRangeSession.parse(null))
        assertNull(Vi530xRangeSession.parse(""))
        assertNull(Vi530xRangeSession.parse("D=12 S=0"))
        assertNull(Vi530xRangeSession.parse("D=abc S=0 C=0"))
        assertNull(Vi530xRangeSession.parse("OK"))
        // A reply carrying a trailing carriage return is still a reading, not a mystery: the same
        // transport that returns CRLF to the installer can reach the daemon socket too.
        assertEquals(42f, Vi530xRangeSession.parse("D=42 S=0 C=1\r"))
    }
}
