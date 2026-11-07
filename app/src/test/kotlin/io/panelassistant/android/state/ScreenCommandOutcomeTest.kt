package io.panelassistant.android.state

import io.panelassistant.android.control.*
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.platform.ScreenPower
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

internal class ScreenCommandOutcomeTest : PanelRuntimeRig() {
    @After fun restoreRenderer() { BuiltinDashboard.onScreenAwake(true) }

    @Test fun offRefusedByAVisibleHoldReachesTheNativeCallerAndRetryRecovers() {
        val rig = rig()
        val owner = Any()
        try {
            assertTrue(rig.screen.acquireVisibleHold(owner))
            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertEquals(false, rig.screen.observedDark())
            rig.screen.releaseVisibleHold(owner)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertEquals(true, rig.screen.observedDark())
        } finally { rig.close() }
    }

    @Test fun visibleSafetyDimIsRefusedAndKeepsTheRendererAwake() {
        val rig = rig(screenWakeTap = FakeWakeTap(canArm = false))
        try {
            BuiltinDashboard.onScreenAwake(true)
            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertEquals(false, rig.screen.observedDark())
            assertTrue(BuiltinDashboard.screenAwakeNow)
        } finally { rig.close() }
    }

    @Test fun brightnessZeroSleepsTheRendererOnlyWhenDarkAndOnRestoresIt() {
        val rig = rig()
        try {
            BuiltinDashboard.onScreenAwake(true)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertFalse("confirmed dark pauses the dashboard", BuiltinDashboard.screenAwakeNow)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(true)))
            assertTrue(BuiltinDashboard.screenAwakeNow)
        } finally { rig.close() }
    }

    @Test fun aClampedRawZeroIsNotAnAppliedOff() {
        val backlight = object : Backlight {
            var level = 160
            override fun getBrightness() = level
            override fun setBrightness(level: Int) { this.level = level }
            override fun setBrightnessRaw(level: Int) { this.level = level.coerceAtLeast(8) }
        }
        val rig = rig(screenBacklight = backlight)
        try {
            BuiltinDashboard.onScreenAwake(true)
            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertEquals(false, rig.screen.observedDark())
            assertTrue(BuiltinDashboard.screenAwakeNow)
        } finally { rig.close() }
    }

    @Test fun onCannotSucceedWhileAndroidRemainsAsleep() {
        val rig = rig(screenOff = ScreenOff.SCREEN_TIMEOUT, screenPower = FakeScreenPower(interactive = false))
        try {
            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(true)))
        } finally { rig.close() }
    }

    @Test fun anOutsideBrightnessWriteWhileOffRestoresItsCommandedLevelRatherThanItsNodeLevel() {
        val backlight = object : Backlight {
            var commandedLevel = 160
            var effective = 160
            override fun getCommanded() = commandedLevel
            override fun getBrightness() = effective
            override fun setBrightness(level: Int) {
                commandedLevel = level
                effective = if (level == 241) 102 else level
            }
            override fun setBrightnessRaw(level: Int) { commandedLevel = level; effective = level }
        }
        val rig = rig(screenBacklight = backlight)
        val observer = BrightnessSettingObserver(
            handler = null,
            initialLevel = 160,
            readSetting = { backlight.commandedLevel },
            levelFromSetting = { it },
            consumeOwnedWrite = { false },
            observedDark = rig.screen::observedDark,
            noteExternal = { _, _ -> fail("an OFF-owned write is not a brightness preference") },
            background = { it() },
            captureOffGeneration = rig.screen::currentOffGeneration,
            handleWhileOff = rig.screen::noteBrightnessWhileOff,
        )
        try {
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            backlight.commandedLevel = 241
            backlight.effective = 102
            observer.onChange(false)
            assertEquals("the outside write does not wake the panel", 0, backlight.effective)

            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(true)))

            assertEquals("ON restores the HA-scale level", 241, backlight.commandedLevel)
            assertEquals(102, backlight.effective)
        } finally { rig.close() }
    }

    @Test fun aFailedOnFromAnOwnedOffKeepsATapRetryThatCanRelightThePanel() =
        assertFailedOnKeepsTapRetry(ScreenOff.BRIGHTNESS_ZERO)

    @Test fun aFailedOnFromABlPowerFallbackKeepsATapRetryThatCanRelightThePanel() =
        assertFailedOnKeepsTapRetry(ScreenOff.DAEMON_BLPOWER)

    private fun assertFailedOnKeepsTapRetry(route: ScreenOff) {
        val backlight = object : Backlight {
            var level = 160
            var wakeWorks = false
            override fun getBrightness() = level
            override fun setBrightness(level: Int) { if (wakeWorks) this.level = level }
            override fun setBrightnessRaw(level: Int) { this.level = level }
        }
        val wakeTap = FakeWakeTap()
        val rig = rig(screenOff = route, screenBacklight = backlight, screenWakeTap = wakeTap,
            screenRoot = FakeRootShell(runResult = false))
        var tapWakes = 0
        rig.screen.onWakeByTap = { tapWakes++ }
        try {
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertFalse(BuiltinDashboard.screenAwakeNow)

            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(true)))

            assertEquals(true, rig.screen.observedDark())
            assertTrue("the failed wake retains an OFF owner", rig.screen.isIntendedOff())
            assertTrue("a person can retry through the consuming overlay", wakeTap.armed)
            assertFalse("a failed wake does not resume the dark dashboard", BuiltinDashboard.screenAwakeNow)
            backlight.wakeWorks = true
            wakeTap.fireTap()
            assertEquals(false, rig.screen.observedDark())
            assertTrue(BuiltinDashboard.screenAwakeNow)
            assertFalse(rig.screen.isIntendedOff())
            assertFalse(wakeTap.armed)
            assertEquals(1, tapWakes)
        } finally { rig.close() }
    }

    @Test fun aRepeatedOffThatDegradesToVisibleDimResumesThePreviouslySleepingRenderer() {
        val backlight = FakeBacklight(160)
        val wakeTap = FakeWakeTap()
        val rig = rig(screenBacklight = backlight, screenWakeTap = wakeTap)
        try {
            BuiltinDashboard.onScreenAwake(true)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))
            assertEquals(true, rig.screen.observedDark())
            assertFalse(BuiltinDashboard.screenAwakeNow)

            wakeTap.canArm = false
            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "screen", CommandValue.Light(false)))

            assertEquals(false, rig.screen.observedDark())
            assertTrue("a visible fallback must resume the previously frozen dashboard", BuiltinDashboard.screenAwakeNow)
        } finally { rig.close() }
    }

    @Test fun backWakesBeforeNavigationAndDoesNotNavigateWhenWakeFails() {
        var interactive = false
        var wakeWorks = false
        val power = object : ScreenPower {
            override fun isInteractive() = interactive
            override fun pulseWake() { if (wakeWorks) interactive = true }
        }
        var backs = 0
        val navigationStates = mutableListOf<Boolean>()
        val navigation = InteractiveController(false, FakeRootShell(available = false),
            accessibility = object : io.panelassistant.android.platform.AccessibilityActions {
                override fun back(): Boolean { navigationStates += interactive; backs++; return true }
                override fun recents() = false
                override fun tap(x: Int, y: Int) = false
            })
        val rig = rig(screenOff = ScreenOff.SCREEN_TIMEOUT, screenPower = power, navigation = navigation)
        try {
            assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "back", CommandValue.Press))
            assertEquals(0, backs)
            wakeWorks = true
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "back", CommandValue.Press))
            assertEquals(1, backs)
            assertEquals(listOf(true), navigationStates)
        } finally { rig.close() }
    }
}
