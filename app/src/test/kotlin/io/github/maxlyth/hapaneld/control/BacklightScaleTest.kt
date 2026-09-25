package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.BacklightRoute
import io.github.maxlyth.hapaneld.hardware.TransferCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklightScaleTest {

    private class MemoryStore : BacklightScale.OwnedLevelStore {
        var owned: BacklightScale.OwnedLevel? = null
        override fun load() = owned
        override fun save(owned: BacklightScale.OwnedLevel) { this.owned = owned }
    }

    private val perceptual = TransferCurve.Gamma(2.2, floor = 10 / 255.0)

    @Test fun identityIsByteIdenticalToTheLinearBacklight() {
        for (route in BacklightRoute.entries) {
            val store = MemoryStore()
            val scale = BacklightScale(TransferCurve.Identity, route, store)
            assertFalse(scale.curved)
            assertSame(TransferCurve.Identity, scale.nodeCurve)
            for (level in 0..255) {
                assertEquals(level, scale.settingFor(level))
                assertEquals(level, scale.levelFromSetting(level))
                scale.recordOwned(level, level)
            }
            assertNull("the linear backlight keeps no record", store.owned)
            assertEquals(-1, scale.levelFromSetting(-1))
            for (max in intArrayOf(15, 100, 255, 1023)) for (actual in 0..max) {
                assertEquals("$route $actual/$max", (actual.toLong() * 255 / max).toInt(), scale.levelFromNode(actual, max))
            }
        }
    }

    @Test fun nodeRouteKeepsTheSettingOnTheHomeAssistantScaleAndCurvesTheNode() {
        val scale = BacklightScale(perceptual, BacklightRoute.NODE, MemoryStore())
        assertTrue(scale.curved)
        assertSame(perceptual, scale.nodeCurve)
        for (level in 0..255) {
            assertEquals(level, scale.settingFor(level))
            assertEquals("Home Assistant reads back its own level", level, scale.levelFromSetting(scale.settingFor(level)))
        }
        assertEquals(perceptual.toLevel(700, 1023), scale.levelFromNode(700, 1023))
    }

    @Test fun settingRouteCurvesTheSettingAndTheNodeFollowsItLinearly() {
        val scale = BacklightScale(perceptual, BacklightRoute.SETTING, MemoryStore())
        assertTrue(scale.curved)
        assertSame(TransferCurve.Identity, scale.nodeCurve)
        assertEquals(0, scale.settingFor(0))
        assertEquals(10, scale.settingFor(1))
        assertEquals(perceptual.toHardware(128), scale.settingFor(128))
        assertEquals(255, scale.settingFor(255))
    }

    @Test fun settingRouteReadsBackEveryLevelHomeAssistantSetExactly() {
        val store = MemoryStore()
        val scale = BacklightScale(perceptual, BacklightRoute.SETTING, store)
        for (level in 0..255) {
            val setting = scale.settingFor(level)
            scale.recordOwned(level, setting)
            assertEquals("level $level", level, scale.levelFromSetting(setting))
            // After a restart the record comes back from the store.
            assertEquals("level $level after restart", level, BacklightScale(perceptual, BacklightRoute.SETTING, store).levelFromSetting(setting))
        }
    }

    @Test fun settingRouteReadsAnExternalChangeThroughTheInverse() {
        val scale = BacklightScale(perceptual, BacklightRoute.SETTING, MemoryStore())
        scale.recordOwned(128, scale.settingFor(128))
        assertEquals(perceptual.toLevel(200), scale.levelFromSetting(200))
        assertEquals(0, scale.levelFromSetting(0))
        assertEquals(255, scale.levelFromSetting(255))
        assertEquals(-1, scale.levelFromSetting(-1))
    }

    @Test fun aRecordWrittenUnderAnotherCurveIsIgnored() {
        val store = MemoryStore()
        store.owned = BacklightScale.OwnedLevel(level = 15, setting = 10)
        val steeper = TransferCurve.Gamma(3.0, floor = 10 / 255.0)
        val scale = BacklightScale(steeper, BacklightRoute.SETTING, store)
        assertEquals(steeper.toHardware(15), 10)
        assertEquals(15, scale.levelFromSetting(10))
        val shallower = TransferCurve.Gamma(1.0, floor = 10 / 255.0)
        assertEquals(shallower.toLevel(10), BacklightScale(shallower, BacklightRoute.SETTING, store).levelFromSetting(10))
    }

    @Test fun settingRouteReadsTheNodeBackOnTheHomeAssistantScale() {
        val scale = BacklightScale(perceptual, BacklightRoute.SETTING, MemoryStore())
        for (level in 0..255) {
            val node = scale.settingFor(level)   // the firmware pushes the setting to a 0..255 node unchanged
            val observed = scale.levelFromNode(node, 255)
            assertEquals("a save and restore of level $level does not drift", node, scale.settingFor(observed))
        }
        for (level in 0..255) {
            // A 10-bit node: the push is linear from the 0..255 setting, so the read undoes that first.
            val setting = scale.settingFor(level)
            val observed = scale.levelFromNode(TransferCurve.Identity.toHardware(setting, 1023), 1023)
            assertEquals("level $level on a 10-bit node", setting, scale.settingFor(observed))
        }
        assertEquals(255, scale.levelFromNode(1023, 1023))
        assertEquals(0, scale.levelFromNode(0, 1023))
    }
}
