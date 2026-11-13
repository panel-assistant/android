package io.panelassistant.android.control

import io.panelassistant.android.Config
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * prevent_idle_dim writes SCREEN_OFF_TIMEOUT on a transition and once on the first run, never again at
 * every start: Android keeps the value across reboots, and an owner may change it in Android settings.
 * Driven through the production [BrightnessController] and [Config] over a fake timeout setting.
 */
internal class PreventIdleDimStartTest : PanelRuntimeRig() {
    private val power = FakeScreenPower().apply { canWrite = true; timeoutMs = 60_000 }
    private val writes = mutableListOf<Int>()
    private val prefs = MemoryPreferences()
    private val context = FakeContext(scratchDirectory("prevent-idle-dim-start").toFile(), prefs)
    private val config: Config = newConfig(prefs, context.contentResolver)
    private val brightness = BrightnessController(
        context,
        FakeDaemon(),
        screenTimeout = ScreenTimeoutController(
            read = { power.timeoutMs },
            write = { writes += it; power.timeoutMs = it; true },
        ),
    )

    @Test fun `a start with the setting on and the timeout changed by the owner writes nothing`() {
        assertTrue(brightness.applyPreventIdleDim(true, config).effective)
        power.timeoutMs = 30_000 // the owner chose 30 s in Android settings
        writes.clear()

        assertNull(brightness.applyPreventIdleDimAtStart(config))

        assertEquals(emptyList<Int>(), writes)
        assertEquals(30_000, power.timeoutMs)
    }

    @Test fun `turning it on writes never once and records it`() {
        config.setPreventIdleDim(false)

        config.setPreventIdleDim(true)
        assertTrue(brightness.applyPreventIdleDim(true, config).effective)

        assertEquals(listOf(NEVER_SCREEN_TIMEOUT_MS), writes)
        assertTrue(config.preventIdleDimApplied)
        assertEquals("the prior timeout is remembered for turning it off", 60_000, config.savedScreenOffTimeout)
    }

    @Test fun `the first run with the default on applies never once and records it`() {
        assertTrue("on by default", config.preventIdleDim)
        assertFalse(config.preventIdleDimApplied)

        val first = brightness.applyPreventIdleDimAtStart(config)
        assertTrue(requireNotNull(first).effective)
        assertEquals(listOf(NEVER_SCREEN_TIMEOUT_MS), writes)
        assertTrue(config.preventIdleDimApplied)

        assertNull("the next start leaves the timeout alone", brightness.applyPreventIdleDimAtStart(config))
        assertEquals(listOf(NEVER_SCREEN_TIMEOUT_MS), writes)
    }

    @Test fun `a first application that failed is tried again at the next start`() {
        val refusing = BrightnessController(
            context,
            FakeDaemon(),
            screenTimeout = ScreenTimeoutController(read = { power.timeoutMs }, write = { false }),
        )
        assertFalse(requireNotNull(refusing.applyPreventIdleDimAtStart(config)).effective)
        assertFalse(config.preventIdleDimApplied)

        assertTrue(requireNotNull(brightness.applyPreventIdleDimAtStart(config)).effective)
        assertTrue(config.preventIdleDimApplied)
    }

    @Test fun `a start with the setting off writes nothing`() {
        config.setPreventIdleDim(false)
        power.timeoutMs = 45_000

        assertNull(brightness.applyPreventIdleDimAtStart(config))

        assertEquals(emptyList<Int>(), writes)
        assertEquals(45_000, power.timeoutMs)
    }

    @Test fun `turning it off restores the remembered timeout`() {
        assertTrue(brightness.applyPreventIdleDim(true, config).effective)
        writes.clear()

        config.setPreventIdleDim(false)
        assertTrue(brightness.applyPreventIdleDim(false, config).effective)

        assertEquals(listOf(60_000), writes)
        assertEquals(60_000, power.timeoutMs)
        assertFalse(config.preventIdleDimApplied)
    }

    /**
     * The process died while the screen-timeout screen-off had the panel asleep, leaving its short timeout
     * live and the real one in the durable displaced record. Putting that back is the app undoing its own
     * temporary change, so it still happens at start even though prevent_idle_dim is not re-applied.
     */
    @Test fun `an interrupted screen-timeout sleep is restored at start`() {
        assertTrue(brightness.applyPreventIdleDim(true, config).effective)
        val held = ConfigDisplacedScreenTimeout(config)
        held.displacedMs = NEVER_SCREEN_TIMEOUT_MS
        power.timeoutMs = 1_000 // the screen-timeout route's short timeout
        writes.clear()
        val timeoutWritesBefore = power.timeoutWrites

        // Service start, in production order: the screen controller is built, then startAdaptive runs.
        ScreenController(
            FakeBacklight(), power, FakeRootShell(), FakeDaemon(),
            route = ScreenOff.SCREEN_TIMEOUT, nap = {}, displacedTimeout = held,
        )
        assertNull(brightness.applyPreventIdleDimAtStart(config))

        assertEquals(NEVER_SCREEN_TIMEOUT_MS, power.timeoutMs)
        assertEquals(-1, held.displacedMs)
        assertEquals("one write: the restore", 1, power.timeoutWrites - timeoutWritesBefore)
        assertEquals("nothing from prevent_idle_dim", emptyList<Int>(), writes)
    }
}
