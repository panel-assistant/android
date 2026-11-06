package io.panelassistant.android.http

import io.panelassistant.android.control.resolveTouchSoundIntent
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.TouchSoundHardware
import io.panelassistant.android.control.TouchSoundState
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.state.StateConverger.Observation
import io.panelassistant.android.testsupport.PanelRuntimeRig
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported touch-sound value must be a pure function of durable intent, everywhere it is reported.
 *
 * That single invariant is what removes the phantom: if no reporting surface can move without the user,
 * an unrelated save posts back exactly what the panel holds, `planDirectConfigMutation` finds nothing
 * changed for the key, and nothing can be journalled for it. The change-plan cases hold those save shapes;
 * the last two drive the state core and external command dispatcher through the runtime rig.
 */
internal class TouchSoundDriftContractTest : PanelRuntimeRig() {
    @Test fun firmwareDriftUnderAnUnrelatedSaveCannotSynthesizeATouchSoundEdit() {
        // Settled at an earlier boot: the user switched touch sound off.
        val intent = false
        // The Configure page was rendered from that intent, and the user then changed only the name.
        val posted = mapOf("touch_sound" to intent.toString(), "friendly_name" to "Alpha")
        // Meanwhile firmware turned SOUND_EFFECTS_ENABLED back on underneath the app.
        val before = mapOf(
            "touch_sound" to resolveTouchSoundIntent(
                persistedIntent = intent,
                controllerRecord = true,
                observedHardware = true,
                registryDefault = true,
            ).enabled.toString(),
            "friendly_name" to "Beta",
        )

        val plan = planDirectConfigMutation(posted, before)

        assertEquals(setOf("friendly_name"), plan.changedKeys)
        assertTrue(plan.changedLive.isEmpty())
    }

    /**
     * The control, so the assertion above is known to be able to go positive: resolve the same save from
     * the observation the way the old fallback did, and the edit appears out of nothing. This is the
     * defect, written down.
     */
    @Test fun resolvingFromTheObservationIsWhatManufacturedTheEdit() {
        val posted = mapOf("touch_sound" to "false", "friendly_name" to "Alpha")
        val before = mapOf("touch_sound" to "true", "friendly_name" to "Beta")

        val plan = planDirectConfigMutation(posted, before)

        assertEquals(setOf("touch_sound", "friendly_name"), plan.changedKeys)
        assertEquals(listOf("touch_sound" to "false"), plan.changedLive)
    }

    @Test fun anExplicitTouchSoundChangeIsStillAChange() {
        val plan = planDirectConfigMutation(
            posted = mapOf("touch_sound" to "false"),
            before = mapOf("touch_sound" to "true"),
        )

        assertEquals(setOf("touch_sound"), plan.changedKeys)
        assertEquals(listOf("touch_sound" to "false"), plan.changedLive)
    }

    /**
     * A save that does not name touch sound cannot change it, whatever the flag has done since.
     *
     * The JSON API accepts a partial payload and the form posts a subset when one tab is saved alone, so
     * the key's absence from the post is the only thing keeping it out of the change set. Worth holding
     * explicitly: this is the same save that used to acquire a touch-sound edit through its baseline.
     */
    @Test fun aSaveThatDoesNotNameTouchSoundLeavesItAlone() {
        val plan = planDirectConfigMutation(
            posted = mapOf("friendly_name" to "Alpha"),
            before = mapOf("touch_sound" to "false", "friendly_name" to "Beta"),
        )

        assertEquals(setOf("friendly_name"), plan.changedKeys)
        assertTrue(plan.changedLive.isEmpty())
    }

    @Test fun theStatePublishedToHomeAssistantIsThePersistedIntent() {
        val hardware = TouchHardware()
        val rig = rig(touchSoundHardware = hardware, configure = { it.commitTouchSound(false) })
        try {
            val reported = CopyOnWriteArrayList<Observation>()
            rig.core.addStateSink { key, value -> if (key == "touch_sound") reported += value }

            // Firmware has enabled sounds despite the durable OFF intent.
            hardware.effects = 1
            rig.core.reconcile("touch_sound")
            drainStatePump()

            assertEquals(Observation.Known("OFF"), reported.lastOrNull())
        } finally { rig.close() }
    }

    @Test fun anExternallyOriginatedChangePersistsAndPublishesTheNewIntent() {
        val hardware = TouchHardware()
        val rig = rig(touchSoundHardware = hardware, configure = { it.commitTouchSound(false) })
        try {
            rig.start()
            val reported = CopyOnWriteArrayList<Observation>()
            rig.core.addStateSink { key, value -> if (key == "touch_sound") reported += value }

            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "touch_sound", "ON"))
            drainStatePump()

            assertTrue("external intent is durable", rig.config.touchSound)
            assertEquals("external intent reaches the hardware", 1, hardware.effects)
            assertEquals(Observation.Known("ON"), reported.lastOrNull())
        } finally { rig.close() }
    }

    private class TouchHardware : TouchSoundHardware {
        @Volatile var effects = 0
        override fun capture() = TouchSoundState(effects)
        override fun enable(): ControlApplyOutcome {
            effects = 1
            return ControlApplyOutcome.APPLIED
        }
        override fun restore(state: TouchSoundState): ControlApplyOutcome {
            effects = state.effectsSetting ?: 0
            return ControlApplyOutcome.APPLIED
        }
        override fun disableConservatively(): ControlApplyOutcome {
            effects = 0
            return ControlApplyOutcome.APPLIED
        }
    }
}
