package io.panelassistant.android.hardware

import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.http.DiagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A WF1589T variant has a WS2812 strip on /dev/ws2812_pwm and no /dev/ledjni; its LED must drive the strip. */
class Ws2812LedControllerTest {

    private class FakeStrip(private val present: Boolean, private val rc: Int = 0) : Ws2812Driver {
        val fills = mutableListOf<List<Int>>()
        var closes = 0
        override fun available() = present
        override fun fill(r: Int, g: Int, b: Int, count: Int): Int { fills += listOf(r, g, b, count); return rc }
        override fun close() { closes++ }
    }

    private class FakeLed(private val present: Boolean) : LedController {
        override fun available() = present
        override fun backend() = if (present) LedBackend.LEDJNI else LedBackend.UNAVAILABLE
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int) = true
        override fun off() = true
    }

    private val wf1589t = BundledProfileFixtures.profile("wf1589t")

    @Test fun stripOnlyBoardReportsTheActiveRgbRouteAndUsesTheDefaultFrameSize() {
        val driver = FakeStrip(present = true)
        val chosen = LedFactory.appDirect(wf1589t, FakeLed(present = false), driver)
        val backend = chosen.backend()
        assertEquals("WS2812 /dev/ws2812_pwm (RGB)", backend.label)
        assertEquals("ok", DiagReader.rgbLedCapability(backend).status)
        assertTrue(chosen.setRgb(255, 40, 255))
        assertEquals(listOf(255, 40, 255, 500), driver.fills.single())
    }

    @Test fun ledjniStillWinsWhereItExists() {
        val ledjni = FakeLed(present = true)
        assertSame(ledjni, LedFactory.appDirect(wf1589t, ledjni, FakeStrip(present = true)))
    }

    @Test fun neitherNodeUsesTheHelperRoute() {
        val helper = FakeLed(present = true)
        val chosen = LedFactory.appDirect(wf1589t, FakeLed(present = false), FakeStrip(present = false), helper)
        assertEquals(LedBackend.LEDJNI, chosen.backend())
        assertTrue(chosen.available())
    }

    @Test fun configuredFrameSizeAppliesToColourAndOff() {
        val driver = FakeStrip(present = true)
        val profile = object : DeviceProfile by wf1589t {
            override val ledCount = 234
        }
        val strip = LedFactory.appDirect(profile, FakeLed(present = false), driver)
        assertTrue(strip.colorCapable())
        assertTrue(strip.setRgb(255, 40, 300))
        assertTrue(strip.off())
        assertEquals(listOf(listOf(255, 40, 255, 234), listOf(0, 0, 0, 234)), driver.fills)
    }

    @Test fun aDriverErrorIsReported() {
        assertFalse(Ws2812LedController(driver = FakeStrip(present = true, rc = -5)).setRgb(1, 2, 3))
    }

    @Test fun closingTheOutputOwnerReleasesTheSelectedStripOnce() {
        val driver = FakeStrip(present = true)
        val strip = LedFactory.appDirect(wf1589t, FakeLed(present = false), driver)
        val effects = LedEffectController(strip)
        effects.close()
        effects.close()
        assertEquals(1, driver.closes)
    }

    @Test fun aDeclaredCurveIsAppliedToTheStrip() {
        val driver = FakeStrip(present = true)
        val halve = object : LedTransfer { override fun toHardware(v: Int) = v / 2 }
        assertTrue(Ws2812LedController(halve, driver, ledCount = 1).setRgb(200, 100, 0))
        assertEquals(listOf(100, 50, 0, 1), driver.fills.single())
    }

    @Test fun theFourBitLedjniStubIsNotAppliedToTheStrip() {
        assertSame(LedTransfer.Identity, LedFactory.fallbackTransfer(LedTransfer.Rk3576FourBit))
    }
}
