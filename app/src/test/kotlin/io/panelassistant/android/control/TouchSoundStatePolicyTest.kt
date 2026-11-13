package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSoundStatePolicyTest {
    @Test fun enablePersistsExactPriorEffectsStateBeforeEnableAndDisableRestoresIt() {
        val events = mutableListOf<String>()
        val prior = TouchSoundState(effectsSetting = null)
        val store = FakeStore(events)
        val hardware = FakeHardware(prior, events)
        val policy = TouchSoundStatePolicy(store, hardware)

        assertEquals(ControlApplyOutcome.APPLIED, policy.enable())
        assertEquals(ControlApplyOutcome.APPLIED, policy.disable())

        assertEquals(
            listOf("retire-legacy-stream", "capture", "save:$prior", "enable", "restore:$prior", "disable-state"),
            events,
        )
        assertEquals(prior, hardware.restored)
        assertEquals(false, policy.recordedState())
        assertNull(store.prior)
    }

    @Test fun persistenceFailurePreventsAnyAudioMutation() {
        val events = mutableListOf<String>()
        val store = FakeStore(events, saveSucceeds = false)
        val hardware = FakeHardware(TouchSoundState(1), events)

        assertEquals(ControlApplyOutcome.FAILED, TouchSoundStatePolicy(store, hardware).enable())
        assertEquals(
            listOf("retire-legacy-stream", "capture", "save:TouchSoundState(effectsSetting=1)"),
            events,
        )
    }

    @Test fun legacyDisableTurnsEffectsOffWithoutChangingOrInventingVolume() {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val hardware = FakeHardware(TouchSoundState(1), events)

        assertEquals(ControlApplyOutcome.APPLIED, TouchSoundStatePolicy(store, hardware).disable())

        assertEquals(listOf("retire-legacy-stream", "disable-conservative", "disable-state"), events)
        assertNull(hardware.restored)
    }

    /**
     * Asserting a durable OFF is not the same operation as switching touch sound off.
     *
     * A transition restores the flag ha-paneld found before it first enabled the click, which is right
     * once. Replaying that capture on every boot would let a panel whose pre-ha-paneld flag was ON restore
     * the very click its persisted intent says to silence, so reassertion writes the intended state
     * directly and leaves the restore memory for a real transition.
     */
    @Test fun assertingDisabledSilencesTheHardwareInsteadOfRestoringAStaleCapture() {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val hardware = FakeHardware(TouchSoundState(1), events)
        val policy = TouchSoundStatePolicy(store, hardware)

        assertEquals(ControlApplyOutcome.APPLIED, policy.enable())
        events.clear()

        assertEquals(ControlApplyOutcome.APPLIED, policy.assertDisabled())

        assertEquals(listOf("disable-conservative", "disable-state"), events)
        assertNull(hardware.restored)
        assertEquals(false, policy.recordedState())
    }

    @Test fun aPanelThatCannotWriteTheFlagReportsThatRatherThanClaimingSuccess() {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val hardware = FakeHardware(TouchSoundState(1), events, disableOutcome = ControlApplyOutcome.UNAVAILABLE)

        assertEquals(ControlApplyOutcome.UNAVAILABLE, TouchSoundStatePolicy(store, hardware).assertDisabled())

        // Nothing was recorded as disabled, because nothing was disabled.
        assertEquals(listOf("retire-legacy-stream", "disable-conservative"), events)
    }

    @Test fun constructingPolicyPerformsOneWayLegacyStreamCleanupWithoutAudioMutation() {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val hardware = FakeHardware(TouchSoundState(0), events)

        TouchSoundStatePolicy(store, hardware)

        assertEquals(listOf("retire-legacy-stream"), events)
        assertNull(hardware.restored)
    }

    // --- startup writes SOUND_EFFECTS_ENABLED only while the intent is first adopted ---

    /** The intent is persisted and the owner then turned the flag off in Android; a restart leaves it. */
    @Test fun `a start with the intent on and the flag changed by the owner writes nothing`() {
        val events = mutableListOf<String>()
        val store = FakeStore(events).apply { enabled = true; prior = TouchSoundState(0) }
        val policy = TouchSoundStatePolicy(store, FakeHardware(TouchSoundState(1), events))
        val intent = resolveTouchSoundIntent(
            persistedIntent = true,
            controllerRecord = policy.recordedState(),
            observedHardware = false,
            registryDefault = false,
        )
        events.clear()

        assertEquals(ControlApplyOutcome.APPLIED, policy.settleAtStart(intent.enabled, intent.needsAdoption))

        assertEquals(emptyList<String>(), events)
    }

    @Test fun `a start with the intent off and the flag changed by the owner writes nothing`() {
        val events = mutableListOf<String>()
        val store = FakeStore(events).apply { enabled = false }
        val policy = TouchSoundStatePolicy(store, FakeHardware(TouchSoundState(1), events))
        events.clear()

        assertEquals(ControlApplyOutcome.APPLIED, policy.settleAtStart(on = false, adopting = false))

        assertEquals(emptyList<String>(), events)
    }

    @Test fun `the first run adopts the intent, writes the flag once and records it`() {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val policy = TouchSoundStatePolicy(store, FakeHardware(TouchSoundState(0), events))
        val intent = resolveTouchSoundIntent(
            persistedIntent = null,
            controllerRecord = policy.recordedState(),
            observedHardware = null,
            registryDefault = true,
        )
        events.clear()

        assertEquals(ControlApplyOutcome.APPLIED, policy.settleAtStart(intent.enabled, intent.needsAdoption))
        assertEquals(listOf("capture", "save:TouchSoundState(effectsSetting=0)", "enable"), events)
        assertEquals(true, policy.recordedState())

        // The next start has a persisted intent, so it writes nothing.
        events.clear()
        val next = resolveTouchSoundIntent(true, policy.recordedState(), false, true)
        assertEquals(ControlApplyOutcome.APPLIED, policy.settleAtStart(next.enabled, next.needsAdoption))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun `turning it on from the owner or Home Assistant writes the flag once`() {
        val events = mutableListOf<String>()
        val store = FakeStore(events).apply { enabled = false }
        val policy = TouchSoundStatePolicy(store, FakeHardware(TouchSoundState(0), events))
        events.clear()

        assertEquals(ControlApplyOutcome.APPLIED, policy.enable())

        assertEquals(1, events.count { it == "enable" })
    }

    private class FakeStore(
        private val events: MutableList<String>,
        private val saveSucceeds: Boolean = true,
    ) : TouchSoundStateStore {
        var enabled: Boolean? = null
        var prior: TouchSoundState? = null
        override fun active() = enabled
        override fun prior() = prior
        override fun saveEnabled(prior: TouchSoundState): Boolean {
            events += "save:$prior"
            if (saveSucceeds) {
                enabled = true
                this.prior = prior
            }
            return saveSucceeds
        }
        override fun saveDisabledAndClearPrior(): Boolean {
            events += "disable-state"
            enabled = false
            prior = null
            return true
        }
        override fun retireLegacyStreamState(): Boolean {
            events += "retire-legacy-stream"
            return true
        }
    }

    private class FakeHardware(
        private val captured: TouchSoundState,
        private val events: MutableList<String>,
        private val disableOutcome: ControlApplyOutcome = ControlApplyOutcome.APPLIED,
    ) : TouchSoundHardware {
        var restored: TouchSoundState? = null
        override fun capture(): TouchSoundState {
            events += "capture"
            return captured
        }
        override fun enable(): ControlApplyOutcome {
            events += "enable"
            return ControlApplyOutcome.APPLIED
        }
        override fun restore(state: TouchSoundState): ControlApplyOutcome {
            events += "restore:$state"
            restored = state
            return ControlApplyOutcome.APPLIED
        }
        override fun disableConservatively(): ControlApplyOutcome {
            events += "disable-conservative"
            return disableOutcome
        }
    }
}
