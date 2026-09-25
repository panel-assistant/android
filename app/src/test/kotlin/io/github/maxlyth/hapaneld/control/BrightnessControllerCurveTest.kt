package io.github.maxlyth.hapaneld.control

import android.content.ContextWrapper
import io.github.maxlyth.hapaneld.device.BacklightRoute
import io.github.maxlyth.hapaneld.hardware.TransferCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller glue between Home Assistant levels, the Android setting and the node, per route. */
class BrightnessControllerCurveTest {

    private class MemorySetting : BrightnessSetting {
        var value: Int? = null
        var refuse: Throwable? = null
        var reject = false
        override fun write(value: Int): Boolean {
            refuse?.let { throw it }
            if (reject) return false
            this.value = value
            return true
        }
        override fun read(): Int? = value
    }

    private class MemoryStore : BacklightScale.OwnedLevelStore {
        var owned: BacklightScale.OwnedLevel? = null
        override fun load() = owned
        override fun save(owned: BacklightScale.OwnedLevel) { this.owned = owned }
    }

    private val perceptual = TransferCurve.Gamma(2.2, floor = 10 / 255.0)
    private val node = "/sys/class/backlight/bl/"

    private fun root() = FakeRootShell(
        outputs = mapOf("ls -d /sys/class/backlight" to node, "cat ${node}max_brightness" to "255"),
        runResult = true,
    )

    private fun controller(curve: TransferCurve, route: BacklightRoute, setting: BrightnessSetting, root: FakeRootShell, store: MemoryStore = MemoryStore()) =
        BrightnessController(object : ContextWrapper(null) {}, root, FakeDaemon(), BacklightScale(curve, route, store), setting)

    private fun nodeWrites(root: FakeRootShell) = root.ran.filter { it.endsWith("${node}brightness") }

    @Test fun anUnprofiledPanelWritesTheLevelToBothActuators() {
        val setting = MemorySetting()
        val root = root()
        val brightness = controller(TransferCurve.Identity, BacklightRoute.NODE, setting, root)
        brightness.setBrightness(128)
        assertEquals(128, setting.value)
        assertEquals(listOf("echo 128 > ${node}brightness"), nodeWrites(root))
        assertEquals(128, brightness.getCommanded())
        assertFalse(brightness.curved)
    }

    @Test fun settingRouteCurvesTheSettingAndReadsBackTheLevelHomeAssistantSet() {
        val setting = MemorySetting()
        val root = root()
        val store = MemoryStore()
        val brightness = controller(perceptual, BacklightRoute.SETTING, setting, root, store)
        for (level in listOf(10, 15, 64, 128, 200, 255)) {
            brightness.setBrightness(level)
            val curved = perceptual.toHardware(level)
            assertEquals("setting for $level", curved, setting.value)
            // ha-paneld's own node write agrees with the firmware's push of the setting.
            assertEquals("echo $curved > ${node}brightness", nodeWrites(root).last())
            assertEquals("read-back of $level", level, brightness.getCommanded())
            assertTrue(brightness.consumeOwnedSettingChange(curved))
        }
        // A process restart keeps the exact read-back.
        assertEquals(255, controller(perceptual, BacklightRoute.SETTING, setting, root(), store).getCommanded())
        // Another app moving the setting is read through the inverse.
        setting.value = 200
        assertEquals(perceptual.toLevel(200), brightness.getCommanded())
        assertEquals(perceptual.toLevel(200), brightness.levelFromSetting(200))
        assertTrue(brightness.curved)
    }

    @Test fun nodeRouteKeepsTheSettingOnTheHomeAssistantScaleAndCurvesTheNode() {
        val setting = MemorySetting()
        val root = root()
        val brightness = controller(perceptual, BacklightRoute.NODE, setting, root)
        brightness.setBrightness(128)
        assertEquals(128, setting.value)
        assertEquals(listOf("echo ${perceptual.toHardware(128)} > ${node}brightness"), nodeWrites(root))
        assertEquals(128, brightness.getCommanded())
    }

    @Test fun zeroIsOffOnEveryRoute() {
        for (route in BacklightRoute.entries) {
            val setting = MemorySetting()
            val root = root()
            val brightness = controller(perceptual, route, setting, root)
            brightness.setBrightnessRaw(0)
            assertEquals(route.name, 0, setting.value)
            assertEquals(route.name, "echo 0 > ${node}brightness", nodeWrites(root).last())
            assertEquals(route.name, 0, brightness.getCommanded())
        }
    }

    @Test fun aFailedSettingWriteKeepsTheLastLevelHomeAssistantSet() {
        val setting = MemorySetting()
        val store = MemoryStore()
        val brightness = controller(perceptual, BacklightRoute.SETTING, setting, root(), store)
        brightness.setBrightness(15)   // collapses onto the floor with its neighbours
        assertEquals(15, brightness.getCommanded())
        setting.reject = true
        brightness.setBrightness(200)
        assertEquals("a rejected write does not claim the setting", 15, brightness.getCommanded())
        setting.reject = false
        setting.refuse = SecurityException("WRITE_SETTINGS not granted")
        brightness.setBrightness(200)
        assertEquals("a refused write does not claim the setting", 15, brightness.getCommanded())
        assertEquals(15, store.owned?.level)
    }

    @Test fun anUnsetSettingReadsAsUnknown() {
        assertEquals(-1, controller(perceptual, BacklightRoute.SETTING, MemorySetting(), root()).getCommanded())
    }
}
