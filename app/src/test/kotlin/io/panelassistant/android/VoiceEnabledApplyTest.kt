package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * applyAcknowledgedVoiceEnabled is the pure decision behind MqttBridge.handleVoiceEnabled: a
 * capability-less panel or a failed durable commit must both report failure (so the caller's
 * LiveSettingApplyResult is FAILED, never APPLIED) and must still reconcile the channel so a stale
 * retained ON is never left echoing a value nothing actually took effect for. Mirrors
 * KioskSettingApplyTest's coverage of the equivalent applyAcknowledgedKioskSetting.
 */
class VoiceEnabledApplyTest {
    @Test fun `an ON request without a microphone is refused and still reconciles`() {
        var committed: Boolean? = null
        var reconciled = false

        val accepted = applyAcknowledgedVoiceEnabled(
            on = true,
            hasMicrophone = false,
            commit = { committed = it; true },
            reconcile = { reconciled = true },
        )

        assertFalse(accepted)
        assertEquals(null, committed)
        assertTrue(reconciled)
    }

    @Test fun `a failed commit is refused and still reconciles`() {
        var reconciled = false

        val accepted = applyAcknowledgedVoiceEnabled(
            on = true,
            hasMicrophone = true,
            commit = { false },
            reconcile = { reconciled = true },
        )

        assertFalse(accepted)
        assertTrue(reconciled)
    }

    @Test fun `an accepted ON commits before reconciling`() {
        val events = mutableListOf<String>()

        val accepted = applyAcknowledgedVoiceEnabled(
            on = true,
            hasMicrophone = true,
            commit = { events += "commit-$it"; true },
            reconcile = { events += "reconcile" },
        )

        assertTrue(accepted)
        assertEquals(listOf("commit-true", "reconcile"), events)
    }

    @Test fun `OFF is accepted even with no microphone capability`() {
        val accepted = applyAcknowledgedVoiceEnabled(
            on = false,
            hasMicrophone = false,
            commit = { true },
            reconcile = {},
        )

        assertTrue(accepted)
    }

    @Test fun `a commit that throws is treated as a failed commit, not an uncaught exception`() {
        var reconciled = false

        val accepted = applyAcknowledgedVoiceEnabled(
            on = true,
            hasMicrophone = true,
            commit = { error("disk full") },
            reconcile = { reconciled = true },
        )

        assertFalse(accepted)
        assertTrue(reconciled)
    }
}
