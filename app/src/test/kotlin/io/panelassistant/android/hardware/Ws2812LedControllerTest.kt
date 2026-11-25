package io.panelassistant.android.hardware

import io.panelassistant.android.device.profile.BundledProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A WF1589T variant has a WS2812 strip on /dev/ws2812_pwm and no /dev/ledjni; its LED must drive the strip. */
class Ws2812LedControllerTest {

    private class FakeStrip(private val present: Boolean, private val rc: Int = 0) : Ws2812Driver {
        val fills = mutableListOf<List<Int>>()
        override fun available() = present
        override fun fill(r: Int, g: Int, b: Int, count: Int): Int { fills += listOf(r, g, b, count); return rc }
    }

    private class FakeLed(private val present: Boolean) : LedController {
        override fun available() = present
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int) = true
        override fun off() = true
    }

    private val wf1589t = BundledProfileFixtures.bundledById.getValue("wf1589t").let {
        io.panelassistant.android.device.profile.DataDeviceProfile(it.document, "", it.rawSha256, trustedBundledContent = true)
    }

    @Test fun stripIsChosenWhenTheBoardHasNoLedjni() {
        val strip = Ws2812LedController(driver = FakeStrip(present = true))
        assertSame(strip, LedFactory.appDirect(wf1589t, FakeLed(present = false), strip))
    }

    @Test fun ledjniStillWinsWhereItExists() {
        val ledjni = FakeLed(present = true)
        assertSame(ledjni, LedFactory.appDirect(wf1589t, ledjni, Ws2812LedController(driver = FakeStrip(present = true))))
    }

    @Test fun neitherNodeFallsBackToTheHelperWhoseAbsenceHidesTheEntity() {
        val chosen = LedFactory.appDirect(wf1589t, FakeLed(present = false), Ws2812LedController(driver = FakeStrip(present = false)))
        assertTrue(chosen is SocketLedController)
    }

    @Test fun colourFillsTheWholeStripAndOffSendsBlack() {
        val driver = FakeStrip(present = true)
        val strip = Ws2812LedController(driver = driver, ledCount = 234)
        assertTrue(strip.colorCapable())
        assertTrue(strip.setRgb(255, 40, 300))
        assertTrue(strip.off())
        assertEquals(listOf(listOf(255, 40, 255, 234), listOf(0, 0, 0, 234)), driver.fills)
    }

    @Test fun aDriverErrorIsReported() {
        assertFalse(Ws2812LedController(driver = FakeStrip(present = true, rc = -5)).setRgb(1, 2, 3))
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
