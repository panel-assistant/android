package io.panelassistant.android.hardware

import io.panelassistant.android.control.FakeDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        assertSame(curve, LedFactory.fallbackTransfer(curve))
        assertSame(LedTransfer.Identity, LedFactory.fallbackTransfer(LedTransfer.Rk3576FourBit))
        assertSame(LedTransfer.Identity, LedFactory.fallbackTransfer(LedTransfer.Identity))
    }

    @Test fun theIdentityCurveIsThePassthrough() {
        assertSame(LedTransfer.Identity, LedTransfer.curved(TransferCurve.Identity))
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

    private class FakeNativeDriver : LedNativeDriver {
        var lastRgb: Triple<Int, Int, Int>? = null
        var rgbResult = 0
        var offResult = 0
        override fun available() = true
        override fun setRgb(r: Int, g: Int, b: Int): Int { lastRgb = Triple(r, g, b); return rgbResult }
        override fun off() = offResult
    }
}
