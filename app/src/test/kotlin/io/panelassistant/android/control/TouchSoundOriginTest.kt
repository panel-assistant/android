package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The origin decision, which is the whole fix.
 *
 * Touch sound used to report `store.active() ?: <the live SOUND_EFFECTS_ENABLED flag>`. That flag is a
 * global any holder of `WRITE_SETTINGS` can write, so a panel with no record tracked firmware instead of
 * its user: the reported value moved on its own, the next unrelated Configure save posted a value that no
 * longer matched, and the live-setting journal queued an apply nobody asked for. These cases pin the
 * order that removes it, one row of the truth table at a time, so a mutation to any single branch fails
 * exactly one of them.
 */
class TouchSoundOriginTest {

    @Test fun persistedIntentOutranksAContradictingObservationInBothDirections() {
        // Firmware turned the platform flag ON under a panel the user had switched OFF.
        val drifted = resolveTouchSoundIntent(
            persistedIntent = false,
            controllerRecord = true,
            observedHardware = true,
            registryDefault = true,
        )
        assertEquals(TouchSoundResolution(false, TouchSoundOrigin.INTENT), drifted)

        // And the mirror image, so the rule is "intent wins" rather than "false wins".
        val driftedOff = resolveTouchSoundIntent(
            persistedIntent = true,
            controllerRecord = false,
            observedHardware = false,
            registryDefault = false,
        )
        assertEquals(TouchSoundResolution(true, TouchSoundOrigin.INTENT), driftedOff)
    }

    @Test fun anUpgradingPanelAdoptsItsOwnActuationRecordBeforeTheHardware() {
        // A store written by an older build: no registry value, but ha-paneld's own record of what it
        // asserted. That record is a decision; the flag beside it may already have drifted.
        val resolved = resolveTouchSoundIntent(
            persistedIntent = null,
            controllerRecord = true,
            observedHardware = false,
            registryDefault = false,
        )
        assertEquals(TouchSoundResolution(true, TouchSoundOrigin.ADOPTED), resolved)
    }

    @Test fun aPanelWithNoRecordAdoptsTheHardwareOnceInEitherState() {
        // The case that never acquired a record at all: startup only wrote one when the flag was already
        // on, so a panel whose firmware ships touch sounds off tracked the flag forever.
        assertEquals(
            TouchSoundResolution(false, TouchSoundOrigin.ADOPTED),
            resolveTouchSoundIntent(null, null, observedHardware = false, registryDefault = true),
        )
        assertEquals(
            TouchSoundResolution(true, TouchSoundOrigin.ADOPTED),
            resolveTouchSoundIntent(null, null, observedHardware = true, registryDefault = false),
        )
    }

    @Test fun aPanelThatCanOfferNothingTakesTheRegistryDefault() {
        assertEquals(
            TouchSoundResolution(true, TouchSoundOrigin.DEFAULT),
            resolveTouchSoundIntent(null, null, null, registryDefault = true),
        )
        assertEquals(
            TouchSoundResolution(false, TouchSoundOrigin.DEFAULT),
            resolveTouchSoundIntent(null, null, null, registryDefault = false),
        )
    }

    @Test fun onlyAnUnresolvedPanelIsEverWrittenDown() {
        assertFalse(resolveTouchSoundIntent(true, null, null, true).needsAdoption)
        assertFalse(resolveTouchSoundIntent(false, null, null, true).needsAdoption)
        assertTrue(resolveTouchSoundIntent(null, true, null, true).needsAdoption)
        assertTrue(resolveTouchSoundIntent(null, null, true, true).needsAdoption)
        assertTrue(resolveTouchSoundIntent(null, null, null, true).needsAdoption)
    }

    /**
     * A failed adoption commit must decide nothing. The resolution is not durable until the write
     * succeeds, so the same evidence has to still be there for the next boot to resolve from — which is
     * only true because adoption reads the controller store and never rewrites it.
     */
    @Test fun adoptionLeavesTheControllerRecordIntactForAnotherBootOrARollback() {
        val store = RecordingStore(active = true)
        val policy = TouchSoundStatePolicy(store, RefusingHardware())

        assertEquals(true, policy.recordedState())
        val resolved = resolveTouchSoundIntent(
            persistedIntent = null,
            controllerRecord = policy.recordedState(),
            observedHardware = false,
            registryDefault = false,
        )

        assertEquals(TouchSoundResolution(true, TouchSoundOrigin.ADOPTED), resolved)
        // Nothing about resolving touched the store, so an older build reading `active` still finds it.
        assertEquals(true, store.active)
        assertEquals(listOf("retire-legacy-stream"), store.writes)
    }

    @Test fun anUnresolvedPanelHasNoRecordToReport() {
        val policy = TouchSoundStatePolicy(RecordingStore(active = null), RefusingHardware())
        assertNull(policy.recordedState())
    }

    private class RecordingStore(var active: Boolean?) : TouchSoundStateStore {
        val writes = mutableListOf<String>()
        override fun active(): Boolean? = active
        override fun prior(): TouchSoundState? = null
        override fun saveEnabled(prior: TouchSoundState): Boolean {
            writes += "save-enabled"
            active = true
            return true
        }
        override fun saveDisabledAndClearPrior(): Boolean {
            writes += "save-disabled"
            active = false
            return true
        }
        override fun retireLegacyStreamState(): Boolean {
            writes += "retire-legacy-stream"
            return true
        }
    }

    private class RefusingHardware : TouchSoundHardware {
        override fun capture(): TouchSoundState? = null
        override fun enable() = ControlApplyOutcome.UNAVAILABLE
        override fun restore(state: TouchSoundState) = ControlApplyOutcome.UNAVAILABLE
        override fun disableConservatively() = ControlApplyOutcome.UNAVAILABLE
    }
}
