package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualBrightnessAuthorityTest {
    @Test fun bootCountIsReadOncePerAuthorityLifetime() {
        var reads = 0
        val subject = ManualBrightnessAuthority(Store(), { wall }, { elapsed }, { reads++; boot })
        subject.capture(180, 100, 100, BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src")
        repeat(1_000) { subject.evaluate(100, "ctx", "src") }
        assertEquals(1, reads)
    }

    @Test fun androidObserverUsesThePriorAppliedLevelNotTheAlreadyChangedSetting() {
        assertEquals(100, priorAppliedBrightness(BrightnessPreferenceOrigin.ANDROID_SYSTEM, 180, 100, 90))
        assertEquals(180, priorAppliedBrightness(BrightnessPreferenceOrigin.HOME_ASSISTANT, 180, 100, 90))
    }

    private class Store(initial: ManualBrightnessPreferenceRecord? = null) : ManualBrightnessPreferenceStore {
        var value = initial
        var saves = 0
        var clears = 0
        override fun load() = value
        override fun save(record: ManualBrightnessPreferenceRecord) { value = record; saves++ }
        override fun clear() { value = null; clears++ }
    }

    private var wall = 1_000_000L
    private var elapsed = 50_000L
    private var boot = 7

    private fun authority(store: Store = Store()) = ManualBrightnessAuthority(
        store = store,
        wallClockMs = { wall },
        elapsedRealtimeMs = { elapsed },
        bootCount = { boot },
    )

    @Test fun explicitLevelWinsAtCaptureAndAutoRetainsTwentyPercentOfLaterChanges() {
        val subject = authority()
        assertTrue(subject.capture(200, 100, 100, BrightnessPreferenceOrigin.HOME_ASSISTANT, "ctx", "src"))

        assertEquals(200, subject.evaluate(100, "ctx", "src").finalTarget)
        assertEquals(220, subject.evaluate(200, "ctx", "src").finalTarget)
        assertEquals(0.20, subject.evaluate(200, "ctx", "src").autoAuthority, 0.0001)
    }

    @Test fun automaticMaximumDoesNotRestrictExplicitManualPreference() {
        val cappedAutomatic = AdaptiveLuxCurve.rawBrightness(10_000.0,
            maximumBrightness = AdaptiveLuxCurve.percentToBrightness(40))
        val subject = authority()
        assertTrue(subject.capture(255, cappedAutomatic, cappedAutomatic,
            BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src"))
        val captured = subject.evaluate(cappedAutomatic, "ctx", "src")
        assertEquals(255, captured.finalTarget)
        assertEquals(cappedAutomatic, captured.automaticTarget)
        elapsed += ManualBrightnessAuthority.DURATION_MS
        assertEquals(cappedAutomatic, subject.evaluate(cappedAutomatic, "ctx", "src").finalTarget)
    }

    @Test fun smoothFadeHasSixtyPercentAuthorityAtTwoHoursAndFullAtFour() {
        val store = Store()
        val subject = authority(store)
        subject.capture(200, 100, 100, BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src")

        elapsed += ManualBrightnessAuthority.DURATION_MS / 2
        val half = subject.evaluate(200, "ctx", "src")
        assertEquals(210, half.finalTarget)
        assertEquals(0.60, half.autoAuthority, 0.0001)

        elapsed += ManualBrightnessAuthority.DURATION_MS / 2
        val expired = subject.evaluate(200, "ctx", "src")
        assertFalse(expired.active)
        assertEquals(200, expired.finalTarget)
        assertTrue(store.clears > 0)
    }

    @Test fun repeatedCommandReplacesRatherThanAccumulates() {
        val store = Store()
        val subject = authority(store)
        subject.capture(200, 100, 100, BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src")
        elapsed += 1_000
        subject.capture(80, 120, 200, BrightnessPreferenceOrigin.HOME_ASSISTANT, "ctx", "src")

        val state = subject.evaluate(120, "ctx", "src")
        assertEquals(80, state.finalTarget)
        assertEquals(BrightnessPreferenceOrigin.HOME_ASSISTANT, state.origin)
        assertEquals(2, store.saves)
    }

    @Test fun tinyCommandDoesNotCreatePreference() {
        val store = Store()
        val subject = authority(store)
        assertFalse(subject.capture(102, 100, 100, BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src"))
        assertFalse(subject.evaluate(100, "ctx", "src").active)
        assertEquals(0, store.saves)
    }

    @Test fun contextOrSourceChangeFailsClosed() {
        val store = Store()
        val subject = authority(store)
        subject.capture(180, 100, 100, BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src")

        assertFalse(subject.evaluate(100, "other", "src").active)
        assertNull(store.value)
    }

    @Test fun sameBootUsesMonotonicTimeAndRebootUsesPlausibleWallTime() {
        val store = Store()
        var subject = authority(store)
        subject.capture(180, 100, 100, BrightnessPreferenceOrigin.PANEL_CONTROLS, "ctx", "src")
        wall -= 500_000
        elapsed += 60_000
        assertTrue(subject.evaluate(100, "ctx", "src").active)

        wall = 1_000_000L + 120_000
        boot++
        subject = authority(store)
        assertTrue(subject.evaluate(100, "ctx", "src").active)
    }

    @Test fun invalidWallClockAfterRebootExpiresRecord() {
        val store = Store()
        var subject = authority(store)
        subject.capture(180, 100, 100, BrightnessPreferenceOrigin.ANDROID_SYSTEM, "ctx", "src")
        boot++
        wall -= 1

        subject = authority(store)
        assertFalse(subject.evaluate(100, "ctx", "src").active)
        assertNull(store.value)
    }
}
