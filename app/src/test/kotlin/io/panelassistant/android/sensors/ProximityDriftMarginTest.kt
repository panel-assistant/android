package io.panelassistant.android.sensors

import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Calibration
import io.panelassistant.android.sensors.ProximityCalibrationEngine.CapabilityStatus
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Mode
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Result
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Stage
import io.panelassistant.android.sensors.ProximityCalibrationEngine.WaveCalibration
import io.panelassistant.android.sensors.ProximityCalibrationEngine.WavePattern
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A calibration's decision boundary has to outlive the clear floor it was measured against.
 *
 * The series replayed here is four recorded days of two-second polling from one raw16 unit. Its
 * saved calibration put the boundary 13.7 counts above the clear stage that derived it; the floor
 * walked across that within a day and the unit then reported near with nobody in front of it, so it
 * could not sleep and a single approach could not rearm its wake.
 */
class ProximityDriftMarginTest {
    /** The clear-floor median of the recorded session, and what a person and a hand actually read. */
    private val clearFloor = 974f
    private val occupant = 3_413f
    private val hand = 49_235f

    /** What that session measured as near: twenty-one counts above its own clear stage. */
    private val weakNear = 995f

    private val margin = proximityDriftMargin(ProximityAcquisition.STK_RAW)

    @Test fun eachRouteCarriesTheDriftBandItsOwnSeriesMeasured() {
        assertEquals(300f, margin, 0f)
        assertEquals(100f, proximityDriftMargin(ProximityAcquisition.ANDROID_HAL), 0f)
        assertEquals(0f, proximityDriftMargin(ProximityAcquisition.VI530X), 0f)
        assertEquals(0f, proximityDriftMargin(ProximityAcquisition.GPIO), 0f)
        assertEquals(0f, proximityDriftMargin(ProximityAcquisition.ABSENT), 0f)
    }

    /**
     * The headline contract, run end to end: put the recorded session's own observations through
     * setup, then replay four recorded days against whatever setup was willing to save. Without the
     * margin, setup saves that pair and the replay reports near with nobody present for hours.
     */
    @Test fun whateverSetupSavesFromTheRecordedSessionStaysClearAcrossTheRecordedDays() {
        val journey = Journey(driftMargin = margin)
        journey.runToReview(clearFloor, weakNear)
        val engine = ProximityCalibrationEngine(journey.saved, observedSourceMode = Mode.RANGED, driftMargin = margin)
        val series = recordedSeries()
        assertTrue("the recorded series must carry four days of samples", series.size > 10_000)
        series.forEach { (atMs, raw) ->
            engine.observe(raw, atMs)
            // The service ticks the engine between samples; without that the debounce window never
            // closes and every reading lands one sample late.
            val state = engine.tick(atMs + SETTLE_MS)
            assertFalse(
                "near at ${atMs / 3_600_000}h with raw $raw, which is this unit's clear floor and its drift",
                state.near == true && raw <= PRESENCE_FLOOR,
            )
        }
    }

    @Test fun theRecordedDaysUnderTheSavedBoundaryAreStuckNearForHoursAtATime() {
        // The defect, replayed: the control that proves the fixture carries it. This holds with and
        // without the margin, and it is what the admission rule exists to stop anyone saving again.
        val engine = ProximityCalibrationEngine(
            Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear),
            observedSourceMode = Mode.RANGED,
        )
        var longestStuckMs = 0L
        var stuckSince: Long? = null
        recordedSeries().forEach { (atMs, raw) ->
            engine.observe(raw, atMs)
            if (engine.tick(atMs + SETTLE_MS).near == true) {
                if (stuckSince == null) stuckSince = atMs
                if (raw <= PRESENCE_FLOOR) longestStuckMs = maxOf(longestStuckMs, atMs - stuckSince!!)
            } else {
                stuckSince = null
            }
        }
        assertTrue(
            "the saved boundary must show hours of near with nobody present, else the fixture is wrong",
            longestStuckMs > 4 * 3_600_000L,
        )
    }

    @Test fun aNearObservationInsideTheDriftBandIsNeverSavedAsACalibration() {
        val journey = Journey(driftMargin = margin)
        journey.begin(clearFloor)
        journey.capture(clearFloor)
        journey.capture(weakNear)
        assertEquals(
            "a separation smaller than the drift band is not a presence measurement",
            Stage.NEAR,
            journey.state.stage,
        )
        journey.tick(ProximityCalibrationEngine.APPROACH_TIMEOUT_MS)
        assertEquals(Stage.WAVE_BASELINE, journey.state.stage)
        assertEquals(CapabilityStatus.NOT_DETECTED, journey.state.presenceStatus)
        assertFalse(journey.state.presenceSupported)
    }

    @Test fun anOccupantSeparationIsSavedAndHoldsItsBoundaryAboveTheDriftBand() {
        val journey = Journey(driftMargin = margin)
        journey.runToReview(clearFloor, occupant)
        val saved = requireNotNull(journey.saved) { "an occupant separation must remain saveable" }
        assertEquals(CapabilityStatus.VALIDATED, journey.state.presenceStatus)
        assertTrue(
            "entering near must sit further above the clear floor than the band it must outlive",
            enterRaw(saved) - saved.clearRaw >= margin,
        )
        assertTrue("leaving near must also sit above the band", exitRaw(saved) - saved.clearRaw >= margin)
    }

    @Test fun anOccupantReadsNearAndAHandCertainlyDoes() {
        val calibration = admitted()
        assertEquals(100, calibration.level(occupant))
        assertEquals(100, calibration.level(hand))
        val engine = ProximityCalibrationEngine(calibration, observedSourceMode = Mode.RANGED, driftMargin = margin)
        engine.observe(clearFloor, 0L)
        assertEquals(false, engine.tick(1_000L).near)
        engine.observe(occupant, 2_000L)
        assertEquals(true, engine.tick(3_000L).near)
        engine.observe(clearFloor, 10_000L)
        assertEquals(false, engine.tick(11_000L).near)
        engine.observe(hand, 12_000L)
        assertEquals(true, engine.tick(13_000L).near)
    }

    @Test fun aFloorDriftingUpAndDownAcrossTheCalibrationValueNeverReportsNear() {
        val engine = ProximityCalibrationEngine(admitted(), observedSourceMode = Mode.RANGED, driftMargin = margin)
        var at = 0L
        listOf(0f, margin, 0f, -100f, 0f, margin, -100f, margin).forEach { offset ->
            repeat(40) {
                at += 60_000L
                val state = engine.observe(clearFloor + offset, at)
                assertEquals("drifted to ${clearFloor + offset}", false, state.near ?: false)
            }
        }
    }

    @Test fun aFloorThatDriftsTheWholeBandWhileOccupiedStillClearsWhenTheyLeave() {
        // Hysteresis holds near until the level falls back to the exit boundary. If only the entry
        // boundary carried the margin, a floor that rose while someone stood there would sit above
        // the exit boundary once they left, and the panel would never clear again.
        // A separation wide enough to admit but narrow enough that the band, not the fraction, sets
        // the boundaries: this is where a panel actually lives once it is not a metre from a wall.
        val modest = requireNotNull(margined(clearFloor, clearFloor + MODEST_SPAN))
        val engine = ProximityCalibrationEngine(modest, observedSourceMode = Mode.RANGED, driftMargin = margin)
        engine.observe(clearFloor, 0L)
        assertEquals(false, engine.tick(1_000L).near)
        engine.observe(clearFloor + MODEST_SPAN, 60_000L)
        assertEquals(true, engine.tick(61_000L).near)
        engine.observe(clearFloor + margin * .95f, 120_000L)
        assertEquals("they left onto a floor that drifted the band", false, engine.tick(121_000L).near)
    }

    @Test fun admissionIsExactlyTheHeadroomRuleNotMerelySomeSeparation() {
        val least = margin * ProximityCalibrationEngine.NEAR_ENTER_DRIFT_HEADROOM /
            ProximityCalibrationEngine.MAXIMUM_NEAR_ENTER
        assertNotNull(margined(clearFloor, clearFloor + least * 1.001f))
        assertNull(margined(clearFloor, clearFloor + least * .999f))
        // The same rule at fixed separations, so a relaxed constant cannot move the rule with it:
        // 500 counts leaves only 1.67 bands of room and 600 leaves two.
        assertNull("500 counts of separation cannot carry a 300-count band", margined(clearFloor, clearFloor + 500f))
        assertNotNull("600 counts of separation can", margined(clearFloor, clearFloor + 600f))
        val admitted = requireNotNull(margined(clearFloor, clearFloor + least * 1.001f))
        assertTrue(
            "entering near must stay a headroom multiple of the band above the floor",
            enterRaw(admitted) - clearFloor >= margin * ProximityCalibrationEngine.NEAR_ENTER_DRIFT_HEADROOM,
        )
        assertTrue("leaving near must also stay above the band", exitRaw(admitted) - clearFloor >= margin)
    }

    @Test fun aSeparationWithRoomToSpareKeepsTheBoundariesItWasGiven() {
        val spacious = requireNotNull(margined(clearFloor, occupant))
        assertEquals(.65f, spacious.nearEnter, 1e-6f)
        assertEquals(.30f, spacious.clearExit, 1e-6f)
    }

    @Test fun aWaveWhoseBaselineCannotHoldTheBandIsNotValidatedEither() {
        val wave = WaveCalibration(pattern = WavePattern.SINGLE, clearRaw = clearFloor, nearRaw = weakNear)
        assertNull(ProximityCalibrationEngine.driftMargined(wave, margin))
        val real = WaveCalibration(pattern = WavePattern.SINGLE, clearRaw = occupant, nearRaw = hand)
        assertNotNull(ProximityCalibrationEngine.driftMargined(real, margin))
    }

    @Test fun aStoredCalibrationInsideTheDriftBandIsDeclinedOnUpgradeNotReinterpreted() {
        val stored = row(Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear))
        val backing = Backing(stored)
        val runtime = runtime(backing, driftMargin = margin)
        runtime.observe(clearFloor, 1_000L, false)
        val status = JSONObject(runtime.json())
        assertEquals("calibration_required", status.getString("phase"))
        assertEquals("none", status.getString("calibrationSource"))
        assertTrue(status.getBoolean("calibrationDriftUnsafe"))
        assertFalse(runtime.isPresenceReady())
        runtime.close()
        assertEquals("the saved observations must survive the decline", stored, backing.row)
        assertEquals(0, backing.writes)
        assertEquals(0, backing.clears)
    }

    @Test fun theSameStoredCalibrationIsHonouredWithoutTheMarginWhichIsTheDefect() {
        val backing = Backing(row(Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear)))
        val runtime = runtime(backing, driftMargin = 0f)
        runtime.observe(clearFloor, 1_000L, false)
        val status = JSONObject(runtime.json())
        assertEquals("ready", status.getString("phase"))
        assertEquals("user", status.getString("calibrationSource"))
        assertFalse(status.getBoolean("calibrationDriftUnsafe"))
        runtime.close()
    }

    @Test fun aStoredCalibrationWithRoomIsMigratedAndStillReportsAsAUserSetup() {
        val stored = row(Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = occupant))
        val backing = Backing(stored)
        val runtime = runtime(backing, driftMargin = margin)
        runtime.observe(clearFloor, 1_000L, false)
        val status = JSONObject(runtime.json())
        assertEquals("ready", status.getString("phase"))
        assertEquals("user", status.getString("calibrationSource"))
        assertFalse(status.getBoolean("calibrationDriftUnsafe"))
        runtime.close()
        assertEquals(stored, backing.row)
        assertEquals(0, backing.writes)
    }

    @Test fun aPanelWhoseSensorReportsNearOrClearOnlyIsUntouched() {
        val binary = Calibration(mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f)
        assertEquals(binary, ProximityCalibrationEngine.driftMargined(binary, margin))
        val wave = WaveCalibration(pattern = WavePattern.SINGLE, clearRaw = 1f, nearRaw = 0f)
        assertEquals(wave, ProximityCalibrationEngine.driftMargined(wave, margin))
    }

    @Test fun aRouteWithNoMeasuredDriftBandKeepsItsCalibrationExactly() {
        val value = Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear)
        assertEquals(value, ProximityCalibrationEngine.driftMargined(value, 0f))
        // Including one whose own boundary the margined rule would never place: a route with no
        // measured band is not a route this rule has anything to say about.
        val steep = value.copy(nearEnter = .9f, clearExit = .5f)
        assertEquals(steep, ProximityCalibrationEngine.driftMargined(steep, 0f))
    }

    private fun admitted() = requireNotNull(margined(clearFloor, occupant))

    private fun margined(clear: Float, near: Float) = ProximityCalibrationEngine.driftMargined(
        Calibration(mode = Mode.RANGED, clearRaw = clear, nearRaw = near), margin,
    )

    private fun enterRaw(value: Calibration) = value.clearRaw + value.nearEnter * (value.nearRaw - value.clearRaw)

    private fun exitRaw(value: Calibration) = value.clearRaw + value.clearExit * (value.nearRaw - value.clearRaw)

    private fun recordedSeries(): List<Pair<Long, Float>> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/proximity-drift/$SERIES")) { "missing $SERIES" }
        return stream.bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") || it.isBlank() }.map { line ->
                val parts = line.trim().split(" ")
                (parts[0].toDouble() * 1_000).toLong() to parts[1].toFloat()
            }.toList()
        }
    }

    private fun runtime(backing: Backing, driftMargin: Float) = ProximityCalibrationRuntime(
        SOURCE, PROFILE, null, Store(backing), elapsed = { 1_000L }, wall = { 1_800_000_000_000L },
        observedSourceMode = Mode.RANGED, driftMargin = driftMargin,
    )

    private class Journey(driftMargin: Float) {
        var now = 0L
        var saved: Calibration? = null
        val engine = ProximityCalibrationEngine(
            null, observedSourceMode = Mode.RANGED, driftMargin = driftMargin,
        ) { saved = it; true }
        val state: Result get() = engine.current()
        fun tick(delta: Long): Result { now += delta; return engine.tick(now) }
        fun begin(clear: Float) {
            engine.start(now)
            engine.observe(clear, now, false, true)
            engine.action("begin", now)
        }
        fun capture(raw: Float) {
            tick(ProximityCalibrationEngine.COUNTDOWN_MS)
            engine.observe(raw, now)
            tick(ProximityCalibrationEngine.CAPTURE_HOLD_MS)
        }

        /** Clear, approach, clear again, then let the wave phase lapse and save whatever is offered. */
        fun runToReview(clear: Float, near: Float) {
            begin(clear)
            capture(clear)
            capture(near)
            if (state.stage == Stage.RETURN_CLEAR) capture(clear) else tick(ProximityCalibrationEngine.APPROACH_TIMEOUT_MS)
            assertEquals(Stage.WAVE_BASELINE, state.stage)
            tick(ProximityCalibrationEngine.WAVE_PHASE_TIMEOUT_MS)
            assertEquals(Stage.REVIEW, state.stage)
            if (state.canSave) engine.action("save", now)
        }
    }

    private class Backing(var row: EntityCatalogStore.ProximityModelRow? = null) {
        var writes = 0
        var clears = 0
    }

    private class Store(private val backing: Backing) : ProximityModelStore {
        override fun readProximityModel(fingerprint: String) = backing.row
        override fun writeProximityBatch(
            model: EntityCatalogStore.ProximityModelRow,
            rollups: List<EntityCatalogStore.ProximityRollupRow>,
            episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
            now: Long,
        ) {
            backing.writes++
            backing.row = model
        }
        override fun clearProximityLearning(fingerprint: String) { backing.clears++; backing.row = null }
        override fun close() = Unit
    }

    private companion object {
        const val SERIES = "raw16-four-day-series.txt"
        const val SOURCE = "sysfs-stk3a5x-raw16-v1"
        const val PROFILE = "profile-r1"

        /** Above this unit's clear floor and every excursion its drift ever made. */
        const val PRESENCE_FLOOR = 1_100f

        /** Long enough for the engine's debounce window to close between recorded samples. */
        const val SETTLE_MS = 200L

        /** Admissible, but narrow enough that the band rather than the fraction sets the boundaries. */
        const val MODEST_SPAN = 700f

        fun row(value: Calibration) = EntityCatalogStore.ProximityModelRow(
            ProximityCalibrationRuntime.fingerprint("$PROFILE|$SOURCE"),
            ProximityCalibrationRuntime.STORAGE_VERSION,
            "explicit-calibration-v${value.version}",
            ProximityCalibrationRuntime.encode(value), true, 1_800_000_000_000L,
        )
    }
}
