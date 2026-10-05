package io.panelassistant.android.sensors

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GpioProximityStreamSessionTest {
    @Test fun negotiatesTheDedicatedDomainAndDeliversOnlyTheRequestedGpio() {
        val replies = "OK\nOK\nOK\nOK\nGPIO 18 0\nGPIO 17 1\nGPIO 18 1\n"
        val commands = ByteArrayOutputStream()
        val values = mutableListOf<Float>()
        var unavailable = 0
        var subscribed = false

        val open = GpioProximityStreamSession.run(
            gpio = 18,
            input = ByteArrayInputStream(replies.toByteArray()),
            output = commands,
            onSubscribed = { subscribed = true },
            onValue = values::add,
            onUnavailable = { unavailable++ },
        )

        assertEquals("GPIOV1\nGPIORESET\nGPIOWATCH 18\nGPIOSUBSCRIBE\n", commands.toString())
        assertEquals(listOf(0f, 1f), values)
        assertEquals(0, unavailable)
        assertEquals(true, subscribed)
        assertEquals(false, open)
    }

    @Test fun failsClosedOnAProtocolMismatch() {
        assertThrows(IllegalStateException::class.java) {
            GpioProximityStreamSession.run(
                gpio = 18,
                input = ByteArrayInputStream("OK\nERR\n".toByteArray()),
                output = ByteArrayOutputStream(),
                onSubscribed = {},
                onValue = {},
            )
        }
    }

    @Test fun acceptsAValidEventThatRacesAheadOfTheSubscribeAcknowledgement() {
        val replies = "OK\nOK\nOK\nGPIOUNAVAILABLE 18\nGPIO 18 1\nOK\nGPIOUNAVAILABLE 18\nGPIO 18 0\n"
        val values = mutableListOf<Float>()
        var unavailable = 0

        GpioProximityStreamSession.run(
            gpio = 18,
            input = ByteArrayInputStream(replies.toByteArray()),
            output = ByteArrayOutputStream(),
            onSubscribed = {},
            onValue = values::add,
            onUnavailable = { unavailable++ },
        )

        assertEquals(listOf(1f, 0f), values)
        assertEquals(2, unavailable)
    }
}
