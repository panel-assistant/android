package io.panelassistant.android.hardware

import io.panelassistant.android.control.FakeDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedControllerTest {
    @Test fun socketBackendRequiresExactOperationAcknowledgement() {
        val daemon = FakeDaemon(mapOf("LEDPROBE" to "sysfs", "RGB 0 128 255" to "OK", "OFF" to "ERR"))
        val led = SocketLedController(daemon)

        assertTrue(led.available())
        assertTrue(led.setRgb(-1, 128, 999))
        assertFalse(led.off())
        assertEquals(listOf("LEDPROBE", "RGB 0 128 255", "OFF"), daemon.sent)
    }

    @Test fun socketBackendAppliesTheProfileTransfer() {
        val daemon = FakeDaemon(mapOf("RGB 56 0 255" to "OK"))
        assertTrue(SocketLedController(daemon, LedTransfer.curved(TransferCurve.Gamma(2.2))).setRgb(128, 0, 255))
        assertEquals(listOf("RGB 56 0 255"), daemon.sent)
    }

    @Test fun ioctlFallbackKeepsADeclaredCurveAndDropsOnlyTheStub() {
        val curve = LedTransfer.curved(TransferCurve.Gamma(2.2))
        val levels = listOf(-1, 0, 64, 128, 255, 999)
        assertEquals(listOf(0, 0, 12, 56, 255, 255), levels.map(LedFactory.fallbackTransfer(curve)::toHardware))
        for (transfer in listOf(LedTransfer.Rk3576FourBit, LedTransfer.Identity)) {
            assertEquals(listOf(0, 0, 64, 128, 255, 255), levels.map(LedFactory.fallbackTransfer(transfer)::toHardware))
        }
    }

    @Test fun theIdentityCurveIsThePassthrough() {
        val transfer = LedTransfer.curved(TransferCurve.Identity)
        assertEquals(listOf(0, 0, 128, 255, 255), listOf(-1, 0, 128, 255, 999).map(transfer::toHardware))
    }

    @Test fun socketProbeDistinguishesAbsentAndLegacyHelpers() {
        assertFalse(SocketLedController(FakeDaemon(mapOf("LEDPROBE" to "none"))).available())
        assertTrue(SocketLedController(FakeDaemon(mapOf("LEDPROBE" to "ERR"), available = true)).available())
        assertFalse(SocketLedController(FakeDaemon(mapOf("LEDPROBE" to "ERR"), available = false)).available())
    }

    @Test fun nativeBackendPropagatesResultsAndAppliesTransfer() {
        val driver = FakeNativeDriver()
        val led = Rk3576LedController(LedTransfer.Rk3576FourBit, driver)

        assertTrue(led.available())
        assertTrue(led.setRgb(255, 128, -1))
        assertEquals(Triple(15, 7, 0), driver.lastRgb)
        driver.rgbResult = -5
        driver.offResult = -6
        assertFalse(led.setRgb(1, 2, 3))
        assertFalse(led.off())
    }

    @Test fun everyProfileTransferWritesTheSameBytesOnEachChannel() {
        val gamma = TransferCurve.Gamma(2.2)
        val points = TransferCurve.Points(listOf(0 to 0, 128 to 32, 255 to 255))
        val expected = mapOf<LedTransfer, (Int) -> Int>(
            LedTransfer.Identity to { it },
            LedTransfer.Rk3576FourBit to { it * 15 / 255 },
            LedTransfer.curved(gamma) to { gamma.toHardware(it) },
            LedTransfer.curved(points) to { points.toHardware(it) },
        )
        for ((transfer, bytes) in expected) {
            val driver = FakeNativeDriver()
            val led = Rk3576LedController(transfer, driver)
            for (v in 0..255) {
                assertTrue(led.setRgb(v, 255 - v, v))
                assertEquals("$transfer at $v", Triple(bytes(v), bytes(255 - v), bytes(v)), driver.lastRgb)
            }
        }
    }

    private class FakeNativeDriver : LedNativeDriver {
        var lastRgb: Triple<Int, Int, Int>? = null
        var rgbResult = 0
        var offResult = 0
        override fun available() = true
        override fun setRgb(r: Int, g: Int, b: Int): Int { lastRgb = Triple(r, g, b); return rgbResult }
        override fun off() = offResult
    }
}
