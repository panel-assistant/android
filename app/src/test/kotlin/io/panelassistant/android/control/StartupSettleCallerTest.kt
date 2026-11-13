package io.panelassistant.android.control

import io.panelassistant.android.Config
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.defaultBool
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service start as production runs it, over durable stores that survive a restart: the start-time
 * migration in [io.panelassistant.android.PaneldService.initializeOnStartupLane], then the live-store
 * migration, then [io.panelassistant.android.ServiceDisplay.startAdaptive]'s idle-dim step and
 * [io.panelassistant.android.ServiceSystemControls.reassertFeedback]'s touch-sound and boot-chime steps.
 * Each [start] builds fresh controllers and a fresh [Config] over the same preferences, as a restart does.
 */
internal class StartupSettleCallerTest : PanelRuntimeRig() {
    private val configPrefs = MemoryPreferences()
    private val controllerPrefs = MemoryPreferences()
    private val context = FakeContext(scratchDirectory("startup-settle").toFile(), configPrefs)

    // Platform state Android keeps across reboots, and every write the app makes to it.
    private var screenTimeoutMs = 60_000
    private var ringVolume = 5
    private var touchFlag: Int? = 1
    private val writes = mutableListOf<String>()

    private var touchActive: Boolean? = null
    private var touchPrior: TouchSoundState? = null
    private var touchWriteSucceeds = true

    private fun start(): Config {
        val config = newConfig(configPrefs, context.contentResolver)
        config.migrateStartupSettleMarkers()
        config.migrateLiveStore()

        BrightnessController(
            context,
            FakeDaemon(),
            screenTimeout = ScreenTimeoutController(
                read = { screenTimeoutMs },
                write = {
                    writes += "timeout:$it"
                    if (timeoutWriteSucceeds) screenTimeoutMs = it
                    timeoutWriteSucceeds
                },
            ),
        ).applyPreventIdleDimAtStart(config)

        val touch = TouchSoundStatePolicy(touchStore, touchHardware)
        settleTouchSoundAtStart(
            config = config,
            recordedState = touch.recordedState(),
            observedHardware = touchFlag?.let { it == 1 },
            registryDefault = requireNotNull(SettingsRegistry.spec("touch_sound")).defaultBool(),
            reassert = touch::settleAtStart,
        )

        BootChimeController(
            configured = { config.silenceBootChime },
            setConfigured = config::setSilenceBootChime,
            stateStore = AndroidBootChimeStateStore(controllerPrefs, config),
            hardware = chimeHardware,
        ).applyPersisted()
        return config
    }

    /** What the last released version leaves behind: settings and snapshots, none of the new markers. */
    private fun seedReleasedInstallation() {
        configPrefs.edit()
            .putInt("config_schema", SettingsRegistry.SCHEMA)
            .putBoolean("touch_sound", true)
            .putInt("saved_screen_off_timeout", 60_000)
            .commit()
        val legacyConfig = newConfig(configPrefs, context.contentResolver)
        assertTrue(AndroidBootChimeStateStore(controllerPrefs, legacyConfig).save(BootChimeState(5, 5, 5, 5, 5)))
        touchActive = true
        touchPrior = TouchSoundState(0)
        // It applied all three at every start.
        screenTimeoutMs = NEVER_SCREEN_TIMEOUT_MS
        ringVolume = 0
        touchFlag = 1
    }

    @Test fun `an upgrade with owner-adjusted values writes nothing at its first start or after`() {
        seedReleasedInstallation()
        // The owner then chose 30 s, raised the ring volume and turned touch sounds off, all in Android.
        screenTimeoutMs = 30_000
        ringVolume = 4
        touchFlag = 0

        val config = start()

        assertEquals(emptyList<String>(), writes)
        assertEquals(30_000, screenTimeoutMs)
        assertEquals(4, ringVolume)
        assertEquals(0, touchFlag)
        assertEquals("the reported touch-sound intent is unchanged", true, config.touchSoundIntent)
        assertTrue(config.preventIdleDim && config.silenceBootChime)

        start()
        assertEquals("a restart writes nothing either", emptyList<String>(), writes)
    }

    @Test fun `a fresh installation applies each default once`() {
        start()

        assertEquals(listOf("timeout:$NEVER_SCREEN_TIMEOUT_MS", "touch:enable", "chime:silence"), writes)
        assertEquals(0, ringVolume)

        writes.clear()
        screenTimeoutMs = 30_000
        ringVolume = 4
        touchFlag = 0
        start()
        assertEquals("later owner changes in Android stay", emptyList<String>(), writes)
    }

    @Test fun `a failed first touch-sound adoption is written again at the next start, until it succeeds`() {
        touchFlag = null // the flag cannot be read, so the registry default is adopted
        touchWriteSucceeds = false

        val first = start()
        assertEquals("the intent is adopted and reported", true, first.touchSoundIntent)
        assertFalse(first.touchSoundSettled)
        assertEquals(1, writes.count { it == "touch:enable" })

        touchWriteSucceeds = true
        val second = start()
        assertEquals("the restart writes the flag again", 2, writes.count { it == "touch:enable" })
        assertTrue(second.touchSoundSettled)
        assertEquals(true, second.touchSoundIntent)

        touchFlag = 0 // the owner turns it off in Android
        start()
        assertEquals("settled: no further startup write", 2, writes.count { it == "touch:enable" })
        assertEquals(0, touchFlag)
    }

    @Test fun `a failed first timeout or chime application after the migration is tried again at the next start`() {
        chimeSilenceSucceeds = false
        timeoutWriteSucceeds = false

        start()
        assertEquals(60_000, screenTimeoutMs)
        assertEquals(5, ringVolume)

        chimeSilenceSucceeds = true
        timeoutWriteSucceeds = true
        writes.clear()
        start()
        assertTrue(writes.contains("timeout:$NEVER_SCREEN_TIMEOUT_MS"))
        assertTrue(writes.contains("chime:silence"))
        assertEquals(0, ringVolume)
    }

    private var chimeSilenceSucceeds = true
    private var timeoutWriteSucceeds = true

    private val chimeHardware = object : BootChimeHardware {
        override fun capture(): BootChimeState = BootChimeState(ringVolume, ringVolume, ringVolume, ringVolume, ringVolume)
        override fun silence(): ControlApplyOutcome {
            writes += "chime:silence"
            if (!chimeSilenceSucceeds) return ControlApplyOutcome.FAILED
            ringVolume = 0
            return ControlApplyOutcome.APPLIED
        }
        override fun restore(state: BootChimeState): ControlApplyOutcome {
            writes += "chime:restore"
            ringVolume = state.ringStream
            return ControlApplyOutcome.APPLIED
        }
    }

    private val touchStore = object : TouchSoundStateStore {
        override fun active(): Boolean? = touchActive
        override fun prior(): TouchSoundState? = touchPrior
        override fun saveEnabled(prior: TouchSoundState): Boolean { touchActive = true; touchPrior = prior; return true }
        override fun saveDisabledAndClearPrior(): Boolean { touchActive = false; touchPrior = null; return true }
        override fun retireLegacyStreamState(): Boolean = true
    }

    private val touchHardware = object : TouchSoundHardware {
        override fun capture(): TouchSoundState = TouchSoundState(touchFlag)
        override fun enable(): ControlApplyOutcome {
            writes += "touch:enable"
            if (!touchWriteSucceeds) return ControlApplyOutcome.FAILED
            touchFlag = 1
            return ControlApplyOutcome.APPLIED
        }
        override fun restore(state: TouchSoundState): ControlApplyOutcome {
            writes += "touch:restore"
            touchFlag = state.effectsSetting
            return ControlApplyOutcome.APPLIED
        }
        override fun disableConservatively(): ControlApplyOutcome {
            writes += "touch:disable"
            touchFlag = 0
            return ControlApplyOutcome.APPLIED
        }
    }
}
