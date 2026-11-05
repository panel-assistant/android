package io.panelassistant.android.sensors

import io.panelassistant.android.ProximityWizardCapabilities
import io.panelassistant.android.R
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.proximityWizardSpeech
import io.panelassistant.android.proximityWizardWaveBaselineHint
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Calibration
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Mode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proximity on the panels whose sensor is an Android HAL counting beyond its declared range: the Sonoff NSPanel 86
 * and the Tuya TPA10. A person where they normally stand reads tens of counts above the empty room there, less than
 * the 100-count drift band a new setup must hold, so the panel is driven here as SensorReporter builds it for that
 * route: the band, the declared 9 cm range, and the rule for calibrations people saved before the band existed.
 */
class ProximityNspanel86SetupTest {
    /** A calibration a person saved before 1.0.0 is what the panel keeps running after the update. */
    @Test fun aPersonMadeCalibrationIsKeptThroughTheUpdateExactlyAsSaved() {
        for (saved in listOf(STANDING_SAVED, CLOSER_SAVED)) {
            val stored = row(saved)
            val backing = Backing(stored)
            val panel = Panel(backing)
            panel.sample(saved.clearRaw)
            val status = panel.status()
            assertEquals("$saved", "ready", status.getString("phase"))
            assertEquals("ready", JSONObject(panel.runtime.statusJson()).getString("state"))
            assertEquals("user", status.getString("calibrationSource"))
            assertFalse(status.getBoolean("calibrationDriftUnsafe"))
            // As saved, not reinterpreted: near enters at the saved .65 of the span and clears at .30.
            assertEquals(false, panel.settle(saved.clearRaw + .29f * (saved.nearRaw - saved.clearRaw)).near)
            assertEquals(true, panel.settle(saved.clearRaw + .66f * (saved.nearRaw - saved.clearRaw)).near)
            assertEquals(true, panel.settle(saved.clearRaw + .5f * (saved.nearRaw - saved.clearRaw)).near)
            assertEquals(false, panel.settle(saved.clearRaw).near)
            assertTrue(panel.runtime.isPresenceReady())
            panel.runtime.close()
            assertEquals(stored, backing.row)
            assertEquals(0, backing.writes)
        }
    }

    /** A backup restored onto the updated panel brings the same calibration back, not a request for setup. */
    @Test fun aRestoredPersonMadeCalibrationIsKeptToo() {
        val backing = Backing()
        val panel = Panel(backing)
        panel.sample(STANDING_SAVED.clearRaw)
        assertEquals("calibration_required", panel.status().getString("phase"))
        backing.row = row(STANDING_SAVED)
        assertTrue(panel.runtime.reloadStored())
        panel.sample(STANDING_SAVED.clearRaw)
        val status = panel.status()
        assertEquals("ready", status.getString("phase"))
        assertEquals("user", status.getString("calibrationSource"))
        assertFalse(status.getBoolean("calibrationDriftUnsafe"))
        assertFalse("the same row again changes nothing", panel.runtime.reloadStored())
    }

    /** Only the counting HAL keeps them: on raw16 a person reads thousands above the floor, so setup can replace it. */
    @Test fun theRaw16RouteStillSetsAsideOneInsideItsBand() {
        assertTrue(proximityKeepsStoredInsideDriftBand(ProximityAcquisition.ANDROID_HAL))
        for (other in ProximityAcquisition.values().filter { it != ProximityAcquisition.ANDROID_HAL }) {
            assertFalse("$other", proximityKeepsStoredInsideDriftBand(other))
        }
        val weak = Calibration(mode = Mode.RANGED, clearRaw = 974f, nearRaw = 995f)
        val backing = Backing(row(weak))
        val panel = Panel(backing, ProximityAcquisition.STK_RAW)
        panel.sample(974f)
        val status = panel.status()
        assertEquals("calibration_required", status.getString("phase"))
        assertTrue(status.getBoolean("calibrationDriftUnsafe"))
    }

    /**
     * Where the person stands separates from the empty room, but by less than the band: that is the answer for this
     * position, given at the end of the first hold, not a countdown that restarts until the approach window runs out.
     */
    @Test fun aStandingApproachInsideTheBandIsRefusedAtTheFirstHold() {
        val panel = Panel(Backing())
        panel.begin(CLEAR)
        panel.capture(CLEAR)
        assertEquals("near", panel.stage())
        val nearStarted = panel.now
        panel.advance(ProximityCalibrationEngine.COUNTDOWN_MS, STANDING)
        assertEquals("hold", panel.status().getString("cue"))
        var restarted = false
        var lastRemaining = Long.MAX_VALUE
        while (panel.stage() == "near" && panel.now - nearStarted < ProximityCalibrationEngine.APPROACH_TIMEOUT_MS) {
            panel.advance(250, STANDING)
            val status = panel.status()
            if (status.getString("stage") != "near") break
            val remaining = status.getLong("cueRemainingMs")
            if (status.getString("cue") != "hold" || remaining > lastRemaining) restarted = true
            lastRemaining = remaining
        }
        assertFalse("the hold countdown restarted for a person standing still", restarted)
        assertEquals("wave_baseline", panel.stage())
        assertEquals("not_detected", panel.status().getString("presenceStatus"))
        assertTrue(
            "refused at the end of the first hold",
            panel.now - nearStarted <= ProximityCalibrationEngine.COUNTDOWN_MS + ProximityCalibrationEngine.CAPTURE_HOLD_MS + 250,
        )
    }

    /** The panel then says why, on screen and aloud, from the status it reads, before testing a hand. */
    @Test fun theRefusalIsWhatThePanelTellsThePerson() {
        val panel = Panel(Backing())
        panel.refused()
        val snapshot = panel.status()
        val presenceStatus = snapshot.getString("presenceStatus")
        assertEquals(R.string.proximity_wizard_presence_refused_hint, proximityWizardWaveBaselineHint(presenceStatus))
        val speech = requireNotNull(
            proximityWizardSpeech(
                stage = snapshot.getString("stage"), awaitingReading = false, mode = snapshot.getString("mode"),
                waveCount = 1, capabilities = ProximityWizardCapabilities.NEITHER, acceptedGestures = 0,
                requiredGestures = 3, presenceStatus = presenceStatus,
            ),
        )
        assertEquals(R.string.proximity_wizard_speech_wave_baseline_refused, speech.textRes)

        // After an approach that was detected, the hand-wave step keeps its usual words.
        assertEquals(R.string.proximity_wizard_hands_down, proximityWizardWaveBaselineHint("validated"))
        assertEquals(
            R.string.proximity_wizard_speech_wave_baseline,
            proximityWizardSpeech("wave_baseline", false, "ranged", 1, ProximityWizardCapabilities.PRESENCE_ONLY, 0, 3, "validated")!!.textRes,
        )
    }

    /**
     * Told to stay where they stand, the person reads their standing value, not the empty room's. The hand wave is
     * measured from there, holds the band on its own, and is what setup saves; the empty room and the person standing
     * never wake the panel afterwards.
     */
    @Test fun aRefusedApproachStillTestsAHandWaveFromWhereThePersonStands() {
        val backing = Backing()
        val panel = Panel(backing)
        panel.refused()
        panel.capture(STANDING)
        assertEquals("wave_capture", panel.stage())
        panel.advance(ProximityCalibrationEngine.COUNTDOWN_MS, HAND)
        panel.advance(400, STANDING)
        assertEquals("waves", panel.stage())
        panel.advance(ProximityCalibrationEngine.COUNTDOWN_MS, STANDING)
        panel.advance(200)
        repeat(ProximityCalibrationEngine.REQUIRED_WAVES) {
            panel.advance(800, HAND)
            panel.advance(400, STANDING)
            panel.advance(150)
        }
        assertEquals("review", panel.stage())
        val review = panel.status()
        assertFalse(review.getBoolean("presenceSupported"))
        assertTrue(review.getBoolean("waveSupported"))
        assertTrue(panel.runtime.localAction("save"))
        assertEquals("saved", panel.stage())
        val saved = requireNotNull(ProximityCalibrationRuntime.decode(requireNotNull(backing.row).snapshotJson))
        assertFalse(saved.presenceSupported)
        val wave = requireNotNull(saved.wave)
        assertTrue("the wave's boundary holds the band", wave.clearExit * (wave.nearRaw - wave.clearRaw) >= HAL_BAND)

        // In use: a hand wakes, the room and the standing person do not, and the room's whole band of drift does not.
        assertFalse(panel.advance(1_500, CLEAR).deliberateGesture)
        for (drifted in listOf(STANDING, CLEAR + HAL_BAND, STANDING + HAL_BAND / 2, CLEAR)) {
            assertFalse("$drifted", panel.advance(1_500, drifted).deliberateGesture)
            assertFalse("$drifted", panel.advance(300).deliberateGesture)
        }
        assertTrue(panel.runtime.isWaveReady())
        panel.advance(800, HAND)
        assertTrue("a hand wakes the panel", panel.advance(300).deliberateGesture)
        panel.runtime.close()
    }

    /**
     * Only a completed hold that the band refused is a place to start the hand wave from. A hand raised while the
     * approach window runs out is a hold no one checked for stillness: taking it as the start would measure the
     * hand going down as a wave, and save a model under which the room and the person both read near.
     */
    @Test fun aHoldCutShortByTheApproachWindowIsNeverTheWaveStart() {
        val panel = Panel(Backing())
        panel.begin(CLEAR)
        panel.capture(CLEAR)
        assertEquals("near", panel.stage())
        val nearStarted = panel.now
        panel.advance(ProximityCalibrationEngine.COUNTDOWN_MS, CLEAR)
        while (panel.now - nearStarted < ProximityCalibrationEngine.APPROACH_TIMEOUT_MS - 4_000L) panel.advance(1_000, CLEAR)
        repeat(5) { panel.advance(1_000, HAND) }
        assertEquals("wave_baseline", panel.stage())
        assertEquals("not_detected", panel.status().getString("presenceStatus"))

        panel.advance(ProximityCalibrationEngine.COUNTDOWN_MS, HAND)
        repeat(6) { panel.advance(1_000, HAND) }
        assertEquals("the raised hand is not the starting position", "wave_baseline", panel.stage())

        // Lowered, the person is back at the empty room's reading, which is still a valid start.
        panel.capture(CLEAR)
        assertEquals("wave_capture", panel.stage())
    }

    private class Panel(backing: Backing, acquisition: ProximityAcquisition = ProximityAcquisition.ANDROID_HAL) {
        var now = 1_000L
        // The three route rules SensorReporter.prepare() gives the runtime.
        val runtime = ProximityCalibrationRuntime(
            SOURCE, PROFILE, null, Store(backing), elapsed = { now }, wall = { 1_800_000_000_000L + now },
            observedSourceMode = if (acquisition == ProximityAcquisition.STK_RAW) Mode.RANGED else null,
            driftMargin = proximityDriftMargin(acquisition),
            driftDeclaredRange = proximityDriftDeclaredRange(acquisition, NSPANEL_86_DECLARED_RANGE),
            keepStoredInsideDriftBand = proximityKeepsStoredInsideDriftBand(acquisition),
        )
        private var session = ""
        fun status() = JSONObject(runtime.json())
        fun stage(): String = status().getString("stage")
        fun sample(raw: Float) = runtime.observe(raw, now, false)
        fun settle(raw: Float): ProximityCalibrationRuntime.Decision { sample(raw); now += 200; return runtime.tick(now) }
        fun advance(delta: Long, raw: Float? = null): ProximityCalibrationRuntime.Decision {
            now += delta
            if (session.isNotEmpty()) { runtime.heartbeat(session); runtime.visible() }
            return if (raw == null) runtime.tick(now) else runtime.observe(raw, now, false)
        }
        fun begin(clear: Float) {
            assertTrue(runtime.start())
            session = status().getString("sessionId")
            assertTrue(runtime.visible())
            runtime.observe(clear, now, false, false, true)
            assertTrue(runtime.localAction("begin"))
            assertEquals("clear", stage())
        }
        fun capture(raw: Float) {
            advance(ProximityCalibrationEngine.COUNTDOWN_MS, raw)
            advance(ProximityCalibrationEngine.CAPTURE_HOLD_MS)
        }
        fun refused() {
            begin(CLEAR)
            capture(CLEAR)
            capture(STANDING)
            assertEquals("wave_baseline", stage())
            assertEquals("not_detected", status().getString("presenceStatus"))
        }
    }

    private class Backing(var row: EntityCatalogStore.ProximityModelRow? = null) { var writes = 0 }

    private class Store(private val backing: Backing) : ProximityModelStore {
        override fun readProximityModel(fingerprint: String) = backing.row
        override fun insertProximityModelIfAbsent(model: EntityCatalogStore.ProximityModelRow): Boolean = false
        override fun writeProximityBatch(
            model: EntityCatalogStore.ProximityModelRow,
            rollups: List<EntityCatalogStore.ProximityRollupRow>,
            episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
            now: Long,
        ) {
            backing.writes++
            backing.row = model
        }
        override fun clearProximityLearning(fingerprint: String) { backing.row = null }
        override fun close() = Unit
    }

    private companion object {
        const val SOURCE = "android-hal|stk3x3x|9.0"
        const val PROFILE = "nspanel-86"

        /** What the NSPanel 86 and TPA10 proximity HALs declare as their maximum range, read from live units. */
        const val NSPANEL_86_DECLARED_RANGE = 9f

        val HAL_BAND = proximityDriftMargin(ProximityAcquisition.ANDROID_HAL)

        /** One NSPanel 86: the empty room, and a person where they stand, about 26 counts apart. */
        const val CLEAR = 131f
        const val STANDING = 157f

        /** A hand at the sensor, well clear of the person and the band. */
        const val HAND = 700f

        /** That unit's saved calibration, recovered from its live raw and level readings. */
        val STANDING_SAVED = Calibration(mode = Mode.RANGED, clearRaw = 131f, nearRaw = 157f)

        /** Another NSPanel 86, saved by a person standing closer than usual, read from its database. Its floor reads 0 to 3. */
        val CLOSER_SAVED = Calibration(mode = Mode.RANGED, clearRaw = 2f, nearRaw = 23f)

        fun row(value: Calibration) = EntityCatalogStore.ProximityModelRow(
            ProximityCalibrationRuntime.calibrationKey(PROFILE, SOURCE),
            ProximityCalibrationRuntime.STORAGE_VERSION,
            "explicit-calibration-v${value.version}",
            ProximityCalibrationRuntime.encode(value), true, 1_800_000_000_000L,
        )
    }
}
