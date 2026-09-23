package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.TouchSoundOrigin
import io.github.maxlyth.hapaneld.control.resolveTouchSoundIntent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported touch-sound value must be a pure function of durable intent, everywhere it is reported.
 *
 * That single invariant is what removes the phantom: if no reporting surface can move without the user,
 * an unrelated save posts back exactly what the panel holds, `planDirectConfigMutation` finds nothing
 * changed for the key, and nothing can be journalled for it. The first two cases prove the invariant and
 * prove it can fail; the rest hold the Android-coupled seams that carry it, which have no JVM harness.
 */
class TouchSoundDriftContractTest {
    private fun source(path: String): String =
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$path").readText()

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

    @Test fun neitherLiveValueProjectionOffersTouchSound() {
        val service = source("PaneldService.kt")
        val configLive = service.substring(
            service.indexOf("private fun currentConfigLiveValues()"),
            service.indexOf("private fun managementProjection("),
        )
        val projected = service.substring(
            service.indexOf("private fun projectLiveValues("),
            service.indexOf("private fun currentConfigLiveValues()"),
        )

        // A key present in either map overrides the persisted registry value on every surface that
        // reports it, so touch sound must not appear in either one.
        assertFalse(configLive.contains("touch_sound"))
        assertFalse(configLive.contains("touchSound"))
        assertFalse(projected.contains("\"touch_sound\" to"))
        assertFalse(projected.contains("touchSound"))
        // The keys that genuinely are controller-owned stay where they were.
        assertTrue(configLive.contains("adb.isPersisted()"))
        assertTrue(configLive.contains("cpu.currentTier(allowRootFallback = false)"))
    }

    @Test fun theStatePublishedToHomeAssistantIsThePersistedIntent() {
        val bridge = source("MqttBridge.kt")

        assertTrue(bridge.contains("""channel("touch_sound", stateTouchSound) { known(if (config.touchSound) "ON" else "OFF") }"""))
        assertFalse(bridge.contains("touchSound.isEnabled()"))
    }

    /**
     * Home Assistant reaches `handleTouchSound` without passing through the HTTP authority, so if it only
     * moved the controller the state channel would publish the old value straight back and flip the
     * switch in Home Assistant. Persist first, exactly like every other live setting.
     */
    @Test fun anMqttOriginatedChangePersistsIntentBeforeItActuates() {
        val bridge = source("MqttBridge.kt")
        val handler = bridge.substring(
            bridge.indexOf("override fun handleTouchSound(payload: String)"),
            bridge.indexOf("override fun handleWatchdog(payload: String)"),
        )

        assertTrue(handler.contains("config.commitTouchSound(on)"))
        assertTrue(handler.indexOf("config.commitTouchSound(on)") < handler.indexOf("touchSound.apply(on)"))
        assertTrue(handler.contains("""stateConverger.reconcile("touch_sound", force = true)"""))
    }

    /**
     * Adoption has to be settled before the server can accept a save, or the first post-upgrade boot
     * could serve a Configure page built from the registry default and take a save against it.
     */
    @Test fun intentIsResolvedBeforeTheServerAcceptsAnySave() {
        val service = source("PaneldService.kt")

        assertTrue(service.contains("val touchSoundIntent = resolveTouchSoundIntentOnce()"))
        assertTrue(
            service.indexOf("val touchSoundIntent = resolveTouchSoundIntentOnce()") <
                // The statement, not the comment above the startup block that names it.
                service.indexOf("\n            server.start()"),
        )
        // Reassertion is two-directional now: a persisted OFF is asserted, not merely left alone.
        assertTrue(service.contains("touchSound.reassert(touchSoundIntent)"))
        assertFalse(service.contains("if (touchSound.isEnabled()) touchSound.set(true)"))
    }

    @Test fun adoptionOnlyEverWritesWhenTheResolutionSaysSo() {
        val service = source("PaneldService.kt")
        val adoption = service.substring(
            service.indexOf("private fun resolveTouchSoundIntentOnce()"),
            service.indexOf("private fun previousLiveSettingValue("),
        )

        assertTrue(adoption.contains("if (resolution.needsAdoption)"))
        assertTrue(adoption.contains("config.commitTouchSound(resolution.enabled)"))
        assertTrue(adoption.contains("persistedIntent = config.touchSoundIntent"))
        assertTrue(adoption.contains("controllerRecord = touchSound.recordedState()"))
        assertTrue(adoption.contains("observedHardware = touchSound.observedPlatformState()"))
        assertEquals(TouchSoundOrigin.INTENT, resolveTouchSoundIntent(true, null, null, false).origin)
    }
}
