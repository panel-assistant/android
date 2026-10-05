package io.panelassistant.android.input

import io.panelassistant.android.device.EvdevButton
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EvdevStreamSessionTest {
    private val key = EvdevButton("/dev/input/event1", 116, grab = true, eventType = "KEYCODE_POWER")
    private val switch = EvdevButton("/dev/input/event8", 14, grab = false, eventType = "KEYCODE_MUTE", sw = true)

    @Test fun everyWatchAndSubscriptionMustBeAcknowledgedBeforeStreaming() {
        val input = ByteArrayInputStream("OK\nOK\nOK\nOK\nOK\nKEY 116 1\nKEY 116 0\nSW 14 1\nSW 14 0\n".toByteArray())
        val output = ByteArrayOutputStream()
        val events = mutableListOf<String>()
        var mode: EvdevStreamSession.Mode? = null

        EvdevStreamSession.run(listOf(key, switch), input, output, { mode = it }, events::add)

        assertEquals("INPUTV3\nWATCHRESET\nWATCH /dev/input/event1 1\nWATCH /dev/input/event8 0\nSUBSCRIBE\n", output.toString())
        assertEquals(listOf("KEYCODE_POWER", "KEYCODE_MUTE", "KEYCODE_MUTE"), events)
        assertEquals(EvdevStreamSession.Mode.RECONFIGURABLE, mode)
    }

    @Test fun eventRacingAheadOfSubscribeAckIsNotLost() {
        val events = mutableListOf<String>()
        EvdevStreamSession.run(
            listOf(key),
            ByteArrayInputStream("OK\nOK\nOK\nKEY 116 1\nOK\n".toByteArray()),
            ByteArrayOutputStream(),
            emit = events::add,
        )
        assertEquals(listOf("KEYCODE_POWER"), events)
    }

    @Test fun olderHelperRemainsUsableButIsMarkedLegacy() {
        var mode: EvdevStreamSession.Mode? = null
        EvdevStreamSession.run(
            listOf(key),
            ByteArrayInputStream("ERR\nERR\nOK\nOK\n".toByteArray()),
            ByteArrayOutputStream(),
            onSubscribed = { mode = it },
            emit = {},
        )
        assertEquals(EvdevStreamSession.Mode.LEGACY, mode)
    }

    @Test fun versionTwoHelperRemainsUsableWithoutClaimingReconfiguration() {
        var mode: EvdevStreamSession.Mode? = null
        EvdevStreamSession.run(
            listOf(key),
            ByteArrayInputStream("ERR\nOK\nOK\nOK\n".toByteArray()),
            ByteArrayOutputStream(),
            onSubscribed = { mode = it },
            emit = {},
        )
        assertEquals(EvdevStreamSession.Mode.VERIFIED, mode)
    }

    @Test fun failedOrMissingAcknowledgementRejectsTheSession() {
        assertThrows(IOException::class.java) {
            EvdevStreamSession.run(listOf(key), ByteArrayInputStream("OK\nERR\nOK\n".toByteArray()), ByteArrayOutputStream(), emit = {})
        }
        assertThrows(IOException::class.java) {
            EvdevStreamSession.run(listOf(key), ByteArrayInputStream("OK\nOK\nERR\n".toByteArray()), ByteArrayOutputStream(), emit = {})
        }
        assertThrows(IOException::class.java) {
            EvdevStreamSession.run(listOf(key), ByteArrayInputStream("OK\nOK\nOK\n".toByteArray()), ByteArrayOutputStream(), emit = {})
        }
    }

    @Test fun malformedUnknownReleaseAndRepeatLinesDoNotBecomeEvents() {
        val events = mutableListOf<String>()
        val lines = "OK\nOK\nOK\nOK\nOK\nKEY 116 2\nKEY 116 0\nKEY 999 1\nSW 14 9\nKEY bad 1\nKEY 116 1 extra\nKEY 116 1\n"
        EvdevStreamSession.run(listOf(key, switch), ByteArrayInputStream(lines.toByteArray()), ByteArrayOutputStream(), emit = events::add)
        assertEquals(listOf("KEYCODE_POWER"), events)
    }

    @Test fun ambiguousProfileMappingsAreRejected() {
        val duplicate = key.copy(node = "/dev/input/event2", eventType = "KEYCODE_F1")
        assertThrows(IllegalArgumentException::class.java) {
            EvdevStreamSession.run(listOf(key, duplicate), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), emit = {})
        }
    }

    @Test fun malformedProfileMappingsCannotWriteHelperCommands() {
        val invalid = listOf(
            key.copy(node = "/dev/input/event1\nREBOOT"),
            key.copy(code = 0),
            key.copy(eventType = "POWER\"}"),
        )
        invalid.forEach { button ->
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                EvdevStreamSession.run(listOf(button), ByteArrayInputStream(ByteArray(0)), output, emit = {})
            }
            assertEquals("", output.toString())
        }
    }
}
