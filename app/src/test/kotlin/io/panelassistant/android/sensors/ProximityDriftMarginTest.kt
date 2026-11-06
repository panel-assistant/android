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
 * Historical drift evidence and the retained wave headroom policy.
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

    @Test fun reducedFourDaySamplesCannotManufactureFreshPresenceEvidence() {
        val journey = Journey(driftMargin = margin)
        journey.runToReview(clearFloor, weakNear)
        assertNotNull("a sustained quiet separation can be saved below the historical drift band", journey.saved)
        val engine = ProximityCalibrationEngine(journey.saved, observedSourceMode = Mode.RANGED, driftMargin = margin)
        val series = recordedSeries()
        assertTrue("the recorded series must carry four days of samples", series.size > 10_000)
        // The fixture retained each minute's extrema, discarding intervening acquisitions. Those
        // gaps cannot certify a four-sample quiet block, and ticks cannot reconstruct the samples.
        series.forEach { (atMs, raw) ->
            val observed = engine.observe(raw, atMs)
            val ticked = engine.tick(atMs + SETTLE_MS)
            assertTrue(observed.available)
            assertNull("reduced history is not a fresh occupancy observation at $atMs", ticked.near)
            assertFalse(ticked.presenceApproach)
        }
    }

    @Test fun theRecordedDaysUnderTheOldFixedBoundaryAreStuckNearForHoursAtATime() {
        // Positive control for the captured regression: explicitly replay the retired fixed
        // hysteresis rather than pretending the reduced extrema are densely observed samples.
        val fixed = Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear)
        var near = false
        var longestStuckMs = 0L
        var stuckSince: Long? = null
        recordedSeries().forEach { (atMs, raw) ->
            near = fixedNear(fixed, raw, near)
            if (near) {
                if (stuckSince == null) stuckSince = atMs
                if (raw <= PRESENCE_FLOOR) longestStuckMs = maxOf(longestStuckMs, atMs - stuckSince)
            } else stuckSince = null
        }
        assertTrue("the old saved boundary must demonstrate hours of false near", longestStuckMs > 4 * 3_600_000L)
    }

    @Test fun aSustainedNearObservationInsideTheOldDriftBandCanBeSaved() {
        val journey = Journey(driftMargin = margin)
        journey.runToReview(clearFloor, weakNear)
        assertEquals(CapabilityStatus.VALIDATED, journey.state.presenceStatus)
        assertTrue(journey.state.presenceSupported)
        assertEquals(weakNear, requireNotNull(journey.saved).nearRaw, 0f)
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

    @Test fun anOccupantReadsNearAndAHandCertainlyDoesAfterSustainedFreshSamples() {
        val calibration = admitted()
        val engine = ProximityCalibrationEngine(calibration, observedSourceMode = Mode.RANGED, driftMargin = margin)
        var now = 0L
        fun hold(raw: Float): Result {
            var result = engine.current()
            repeat(33) { result = engine.observe(raw + if (it % 2 == 0) -10f else 10f, now); now += 250 }
            return result
        }
        assertEquals(false, hold(clearFloor).near)
        assertEquals(true, hold(occupant).near)
        assertEquals(false, hold(clearFloor).near)
        assertEquals(true, hold(hand).near)
    }

    @Test fun fixedWaveBoundariesKeepTheirHistoricalDriftHeadroom() {
        val fixed = admitted()
        listOf(0f, margin, 0f, -100f, 0f, margin, -100f, margin).forEach { offset ->
            assertFalse("the retained wave policy still carries its drift headroom", fixedNear(fixed, clearFloor + offset, false))
        }
    }

    @Test fun aFloorDriftingWhileOccupiedStillClearsWhenTheyLeave() {
        for (ambientDrift in listOf(3f, margin * .95f)) {
            val calibration = requireNotNull(margined(clearFloor, clearFloor + MODEST_SPAN))
            val engine = ProximityCalibrationEngine(calibration, observedSourceMode = Mode.RANGED, driftMargin = margin)
            var now = 0L
            fun hold(raw: Float, seconds: Int = 8): Result {
                var result = engine.current()
                repeat(seconds * 4 + 1) {
                    result = engine.observe(raw + if (it % 2 == 0) -10f else 10f, now)
                    now += 250
                }
                return result
            }
            assertEquals(false, hold(clearFloor).near)
            assertEquals(true, hold(clearFloor + MODEST_SPAN).near)
            repeat(480) {
                engine.observe(clearFloor + MODEST_SPAN + ambientDrift * it / 480f + if (it % 2 == 0) -10f else 10f, now)
                now += 250
            }
            assertEquals(true, engine.current().near)
            val departed = hold(clearFloor + ambientDrift)
            assertEquals("they left onto a floor that drifted by $ambientDrift", false, departed.near)
            assertEquals(calibration, engine.current().calibration)
        }
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

    @Test fun aStoredPresenceCalibrationInsideTheOldDriftBandIsAcceptedWithoutRewritingAnchors() {
        val stored = row(Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear))
        val backing = Backing(stored)
        val runtime = runtime(backing, driftMargin = margin)
        runtime.observe(clearFloor, 1_000L, false)
        val status = JSONObject(runtime.json())
        assertEquals("ready", status.getString("phase"))
        assertEquals("user", status.getString("calibrationSource"))
        assertFalse(status.getBoolean("calibrationDriftUnsafe"))
        assertFalse(runtime.isPresenceReady())
        runtime.close()
        assertEquals("the saved observations must remain unchanged", stored, backing.row)
        assertEquals(0, backing.writes)
        assertEquals(0, backing.clears)
    }

    @Test fun theSameStoredPresenceCalibrationIsAlsoHonouredWithoutTheWaveMargin() {
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

    @Test fun aRestoredWeakPresenceCalibrationIsAcceptedAsAStartedOneIs() {
        val good = row(Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = occupant))
        val backing = Backing(good)
        val runtime = runtime(backing, driftMargin = margin)
        runtime.observe(clearFloor, 1_000L, false)
        assertEquals("ready", JSONObject(runtime.json()).getString("phase"))

        val unsafe = row(Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear))
        backing.row = unsafe
        assertTrue(runtime.reloadStored())
        runtime.observe(clearFloor, 1_000L, false)
        val declined = JSONObject(runtime.json())
        assertEquals("ready", declined.getString("phase"))
        assertEquals("user", declined.getString("calibrationSource"))
        assertFalse(declined.getBoolean("calibrationDriftUnsafe"))
        assertFalse(runtime.isPresenceReady())
        assertFalse("a second reload of the same row changes nothing", runtime.reloadStored())
        assertEquals(unsafe, backing.row)

        backing.row = good
        assertTrue(runtime.reloadStored())
        runtime.observe(clearFloor, 1_000L, false)
        val restored = JSONObject(runtime.json())
        assertEquals("ready", restored.getString("phase"))
        assertEquals("user", restored.getString("calibrationSource"))
        assertFalse(restored.getBoolean("calibrationDriftUnsafe"))
        assertEquals(0, backing.writes)
        runtime.close()
    }

    @Test fun aPanelWhoseSensorReportsNearOrClearOnlyIsUntouched() {
        val binary = Calibration(mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f)
        assertEquals(binary, ProximityCalibrationEngine.driftMargined(binary, margin))
        val wave = WaveCalibration(pattern = WavePattern.SINGLE, clearRaw = 1f, nearRaw = 0f)
        assertEquals(wave, ProximityCalibrationEngine.driftMargined(wave, margin))
        // Near-or-clear is exempt because it is near or clear, not because one and zero happen to
        // sit inside a declared range. On a route that is counts by construction (declared range 0)
        // and under a band small enough to fit between them, they must still be left alone.
        assertEquals(binary, ProximityCalibrationEngine.driftMargined(binary, .4f, 0f))
        assertEquals(wave, ProximityCalibrationEngine.driftMargined(wave, .4f, 0f))
    }

    @Test fun aSensorReportingCentimetresWithinItsDeclaredRangeKeepsWorking() {
        // The Android proximity contract is centimetres up to the sensor's declared maximum range, so a
        // conforming HAL reports something like five and zero. A hundred-count band cannot be imposed on
        // five centimetres without refusing every calibration on hardware that worked, so readings inside
        // the declared range are left alone.
        val hal = proximityDriftMargin(ProximityAcquisition.ANDROID_HAL)
        val centimetres = Calibration(mode = Mode.RANGED, clearRaw = 5f, nearRaw = 0f)
        assertEquals(centimetres, ProximityCalibrationEngine.driftMargined(centimetres, hal, 5f))
        val centimetreWave = WaveCalibration(pattern = WavePattern.SINGLE, clearRaw = 5f, nearRaw = 0f)
        assertEquals(centimetreWave, ProximityCalibrationEngine.driftMargined(centimetreWave, hal, 5f))
        // A HAL that counts beyond its declared range, which is what every measured unit does, gets the band.
        val counts = requireNotNull(
            ProximityCalibrationEngine.driftMargined(
                Calibration(mode = Mode.RANGED, clearRaw = 19f, nearRaw = 900f), hal, NSPANEL_86_DECLARED_RANGE,
            ),
        )
        assertTrue(
            "a counting HAL must still hold its boundary above the band",
            counts.clearExit * (counts.nearRaw - counts.clearRaw) >= hal,
        )
        assertNull(
            "and a counting HAL whose approach is too weak is still declined",
            ProximityCalibrationEngine.driftMargined(
                Calibration(mode = Mode.RANGED, clearRaw = 464f, nearRaw = 550f), hal, NSPANEL_86_DECLARED_RANGE,
            ),
        )
    }

    /**
     * The NSPanel 86 shape: its HAL declares a 9 cm range and reads an empty room at 95 to 151 counts on one unit. A
     * setup there that saw 131 clear and 157 near (the separation one such unit holds today, recovered from
     * its live raw and level readings) is far smaller than the 56-count band that unit's own floor walked, so
     * the retained wave headroom helper refuses it. Presence instead validates fresh statistical separation.
     * The calibration that unit already holds is kept as saved (ProximityNspanel86SetupTest).
     */
    @Test fun aCountingHalBelowAnySizeThresholdStillHoldsTheBand() {
        val hal = proximityDriftMargin(ProximityAcquisition.ANDROID_HAL)
        val standing = Calibration(mode = Mode.RANGED, clearRaw = 131f, nearRaw = 157f)
        assertNull(ProximityCalibrationEngine.driftMargined(standing, hal, NSPANEL_86_DECLARED_RANGE))
        val standingWave = WaveCalibration(pattern = WavePattern.SINGLE, clearRaw = 131f, nearRaw = 157f)
        assertNull(ProximityCalibrationEngine.driftMargined(standingWave, hal, NSPANEL_86_DECLARED_RANGE))
        // The same pair from a sensor declaring a range that covers it would be centimetres and is left alone.
        assertEquals(standing, ProximityCalibrationEngine.driftMargined(standing, hal, 200f))

        // Through the runtime, as the panel loads one saved before the band: kept exactly as saved, the row untouched.
        val stored = row(standing)
        val backing = Backing(stored)
        val runtime = ProximityCalibrationRuntime(
            SOURCE, PROFILE, null, Store(backing), elapsed = { 1_000L }, wall = { 1_800_000_000_000L },
            observedSourceMode = Mode.RANGED, driftMargin = hal, driftDeclaredRange = NSPANEL_86_DECLARED_RANGE,
            keepStoredInsideDriftBand = proximityKeepsStoredInsideDriftBand(ProximityAcquisition.ANDROID_HAL),
        )
        runtime.observe(140f, 1_000L, false)
        val status = JSONObject(runtime.json())
        assertEquals("ready", status.getString("phase"))
        assertEquals("user", status.getString("calibrationSource"))
        assertFalse(status.getBoolean("calibrationDriftUnsafe"))
        runtime.close()
        assertEquals(stored, backing.row)
        assertEquals(0, backing.writes)
    }

    @Test fun onlyAHalReadsItsDeclaredRangeAsTheLimitOfCentimetres() {
        assertEquals(9f, proximityDriftDeclaredRange(ProximityAcquisition.ANDROID_HAL, 9f), 0f)
        assertEquals(Float.POSITIVE_INFINITY, proximityDriftDeclaredRange(ProximityAcquisition.ANDROID_HAL, null), 0f)
        assertEquals(Float.POSITIVE_INFINITY, proximityDriftDeclaredRange(ProximityAcquisition.ANDROID_HAL, Float.NaN), 0f)
        assertEquals(0f, proximityDriftDeclaredRange(ProximityAcquisition.STK_RAW, 9f), 0f)
    }

    @Test fun aRouteWithNoMeasuredDriftBandKeepsItsCalibrationExactly() {
        val value = Calibration(mode = Mode.RANGED, clearRaw = clearFloor, nearRaw = weakNear)
        assertEquals(value, ProximityCalibrationEngine.driftMargined(value, 0f))
        // Including one whose own boundary the margined rule would never place: a route with no
        // measured band is not a route this rule has anything to say about.
        val steep = value.copy(nearEnter = .9f, clearExit = .5f)
        assertEquals(steep, ProximityCalibrationEngine.driftMargined(steep, 0f))
    }

    private fun fixedNear(calibration: Calibration, raw: Float, previous: Boolean): Boolean {
        val level = calibration.level(raw)
        return if (previous) level > calibration.clearExit * 100f else level >= calibration.nearEnter * 100f
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
            repeat((ProximityCalibrationEngine.CAPTURE_HOLD_MS / 250L).toInt()) {
                tick(250)
                engine.observe(raw, now)
            }
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
        override fun clearProximityLearning(fingerprint: String) { backing.clears++; backing.row = null }
        override fun close() = Unit
    }

    private companion object {
        const val SERIES = "raw16-four-day-series.txt"
        const val SOURCE = "sysfs-stk3a5x-raw16-v1"
        const val PROFILE = "profile-r1"

        /** What a Sonoff NSPanel 86's proximity HAL declares as its maximum range, read from a live unit. */
        const val NSPANEL_86_DECLARED_RANGE = 9f

        /** Above this unit's clear floor and every excursion its drift ever made. */
        const val PRESENCE_FLOOR = 1_100f

        /** Timers between reduced samples must not contribute missing statistical evidence. */
        const val SETTLE_MS = 200L

        /** Admissible, but narrow enough that the band rather than the fraction sets the boundaries. */
        const val MODEST_SPAN = 700f

        fun row(value: Calibration) = EntityCatalogStore.ProximityModelRow(
            ProximityCalibrationRuntime.calibrationKey(PROFILE, SOURCE),
            ProximityCalibrationRuntime.STORAGE_VERSION,
            "explicit-calibration-v${value.version}",
            ProximityCalibrationRuntime.encode(value), true, 1_800_000_000_000L,
        )
    }
}
