package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTimeoutControllerTest {
    @Test fun enablingSavesTheFiniteTimeoutAndVerifiesNever() {
        var timeout = 60_000
        var saved = -1
        val controller = ScreenTimeoutController(read = { timeout }, write = { timeout = it; true })

        val result = controller.apply(enabled = true, savedTimeoutMs = saved) { saved = it }

        assertEquals(60_000, saved)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, timeout)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, result.observedMs)
        assertTrue(result.effective)
    }

    @Test fun rejectedWriteIsReportedAsIneffective() {
        val controller = ScreenTimeoutController(read = { 60_000 }, write = { false })

        val result = controller.apply(enabled = true, savedTimeoutMs = -1) { }

        assertFalse(result.writeAccepted)
        assertFalse(result.effective)
        assertEquals(60_000, result.observedMs)
    }

    @Test fun rejectedRedundantWriteIsStillEffectiveWhenReadbackAlreadyMatches() {
        val controller = ScreenTimeoutController(read = { NEVER_SCREEN_TIMEOUT_MS }, write = { false })

        val result = controller.apply(enabled = true, savedTimeoutMs = 60_000) { }

        assertFalse(result.writeAccepted)
        assertTrue(result.effective)
        assertEquals(NEVER_SCREEN_TIMEOUT_MS, result.observedMs)
    }

    @Test fun acceptedWriteWithMismatchingReadbackIsReportedAsIneffective() {
        val controller = ScreenTimeoutController(read = { 60_000 }, write = { true })

        val result = controller.apply(enabled = true, savedTimeoutMs = -1) { }

        assertTrue(result.writeAccepted)
        assertFalse(result.effective)
        assertEquals(60_000, result.observedMs)
    }

    @Test fun disablingRestoresSavedTimeoutOrTheSafeDefault() {
        var timeout = NEVER_SCREEN_TIMEOUT_MS
        val controller = ScreenTimeoutController(read = { timeout }, write = { timeout = it; true })

        assertEquals(120_000, controller.apply(false, 120_000) { }.observedMs)
        timeout = NEVER_SCREEN_TIMEOUT_MS
        assertEquals(60_000, controller.apply(false, -1) { }.observedMs)
    }

    @Test fun diagnosticsDistinguishEffectiveMismatchAndDisabledState() {
        assertEquals("on · timeout never", preventIdleDimDiagnostic(true, NEVER_SCREEN_TIMEOUT_MS))
        assertEquals("on · timeout 60s (not applied)", preventIdleDimDiagnostic(true, 60_000))
        assertEquals("off · timeout 60s", preventIdleDimDiagnostic(false, 60_000))
        assertEquals("on · timeout unknown", preventIdleDimDiagnostic(true, -1))
    }
}
