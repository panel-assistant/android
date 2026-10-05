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
