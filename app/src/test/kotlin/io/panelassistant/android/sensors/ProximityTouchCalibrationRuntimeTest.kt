package io.panelassistant.android.sensors

import io.panelassistant.android.PresenceMode
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The runtime calibrates a ranged source from touches in Automatic and Hybrid, and leaves Manual alone. */
class ProximityTouchCalibrationRuntimeTest {
    private class MemoryModels : ProximityModelStore {
        var row: EntityCatalogStore.ProximityModelRow? = null
        override fun readProximityModel(fingerprint: String) = row?.takeIf { it.fingerprint == fingerprint }
        override fun writeProximityBatch(model: EntityCatalogStore.ProximityModelRow, rollups: List<EntityCatalogStore.ProximityRollupRow>,
            episodes: List<EntityCatalogStore.ProximityEpisodeRow>, now: Long) { row = model }
        override fun clearProximityLearning(fingerprint: String) { if (row?.fingerprint == fingerprint) row = null }
        override fun insertProximityModelIfAbsent(model: EntityCatalogStore.ProximityModelRow) = false
        override fun close() = Unit
    }

    private class MemoryTouches : ProximityTouchStore {
        val values = mutableMapOf<String, String>()
        var writes = 0
        override fun read(key: String) = values[key]
        override fun write(key: String, json: String) { values[key] = json; writes++ }
    }

    private fun runtime(mode: PresenceMode, touches: ProximityTouchStore = MemoryTouches(), models: ProximityModelStore = MemoryModels()) =
        ProximityCalibrationRuntime(
            sourceIdentity = "android_hal|test", profileId = "generic", profileBaseline = null, store = models,
            elapsed = { 0L }, wall = { 0L }, presenceMode = { mode }, touchStore = touches,
        )

    /** Replays a capture from [offset]; returns presence decisions keyed by capture time. */
    private fun ProximityCalibrationRuntime.replay(name: String, offset: Long): Map<Long, Boolean?> {
        val decisions = sortedMapOf<Long, Boolean?>()
        for ((at, raw) in ProximityTouchReplay.load(name).events()) {
            if (raw == null) touch(offset + at) else decisions[at] = observe(raw, offset + at, sparseReporting = false).near
        }
        return decisions
    }

    private fun Map<Long, Boolean?>.at(time: Long): Boolean? = entries.last { it.key <= time }.value

    // Source-text reason: openapi.json is the published response schema, read as data.
    @Test fun thePublishedStatusSchemaDeclaresTheStatesAnUncalibratedPanelReports() {
        val declared = JSONObject(TestSources.appFile("src/main/assets/openapi.json").readText())
            .getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get").getJSONObject("responses")
            .getJSONObject("200").getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
            .getJSONObject("properties").getJSONObject("proximity").getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").let { values -> (0 until values.length()).map { values.getString(it) } }
        for (mode in PresenceMode.entries) {
            val runtime = runtime(mode)
            runtime.observe(500f, 1, sparseReporting = false)
            val state = JSONObject(runtime.statusJson()).getString("state")
            assertTrue("$mode reports $state, which the published schema does not declare", state in declared)
        }
    }

    @Test fun automaticCalibratesItselfFromTouchesAndThenTracksPresence() {
        for (name in listOf("office-taps.csv", "x2i-taps.csv")) {
            val runtime = runtime(PresenceMode.AUTOMATIC)
            runtime.observe(500f, 1, sparseReporting = false)
            assertEquals(name, "learning", JSONObject(runtime.statusJson()).getString("state"))
            runtime.replay(name, offset = 1_000)
            assertTrue(name, runtime.isLearnedSignal())
            assertEquals(name, "touch", JSONObject(runtime.json()).getString("calibrationSource"))
            assertEquals(name, "near_is_higher", JSONObject(runtime.json()).getString("polarity"))
            val second = runtime.replay(name, offset = 1_000_000)
            val touches = ProximityTouchReplay.load(name).touches
            for (touch in touches) assertEquals("$name present after touch $touch", true, second.at(touch + 3_000))
            // Between visits the room is empty: presence must clear before the next approach.
            for ((touch, next) in touches.zipWithNext()) assertTrue("$name away after touch $touch",
                second.filterKeys { it in (touch + 8_000)..(next - 6_000) }.values.any { it == false })
        }
    }

    @Test fun touchesRightAfterAWaveWakeAreNotLearnedFrom() {
        // A saved wave calibration lets the sensor itself wake the screen; the touches that follow are circular.
        val wave = ProximityCalibrationEngine.WaveCalibration(clearRaw = 30f, nearRaw = 600f)
        val saved = ProximityCalibrationEngine.Calibration(
            mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 30f, nearRaw = 50f, wave = wave,
        )
        fun replayAfterEachWave(acceptWake: Boolean): ProximityTouchLearner.Model? {
            val runtime = ProximityCalibrationRuntime(
                sourceIdentity = "android_hal|test", profileId = "generic", profileBaseline = saved, store = MemoryModels(),
                elapsed = { 0L }, wall = { 0L }, presenceMode = { PresenceMode.AUTOMATIC }, touchStore = MemoryTouches(),
            )
            val replay = ProximityTouchReplay.load("office-taps.csv")
            for (i in 0 until 40) runtime.observe(37f, i * 250L, sparseReporting = false)
            var last = 10_000L
            for ((at, raw) in replay.events()) {
                val t = 20_000 + at
                if (raw != null) { runtime.observe(raw, t, sparseReporting = false); last = t; continue }
                // A wave between the last reading and the touch woke the screen.
                val pulse = (1..5).map { runtime.observe(600f, last + it * 50L, sparseReporting = false) }
                    .any { it.deliberateGesture }
                assertTrue("the wave fires", pulse)
                runtime.completeGesture(runtime.gestureToken(), accepted = acceptWake)
                runtime.touch(t)
            }
            return runtime.touchLearning()
        }
        assertEquals(null, replayAfterEachWave(acceptWake = true))
        // Control: the same touches after a wave that did not wake the screen do teach the panel.
        assertTrue(replayAfterEachWave(acceptWake = false) != null)
    }

    @Test fun manualNeverLearnsFromTouches() {
        val runtime = runtime(PresenceMode.MANUAL)
        runtime.replay("office-taps.csv", offset = 1_000)
        assertFalse(runtime.isLearnedSignal())
        assertEquals("calibration_required", JSONObject(runtime.statusJson()).getString("state"))
    }

    @Test fun learningSurvivesARestart() {
        val touches = MemoryTouches()
        runtime(PresenceMode.AUTOMATIC, touches).replay("office-taps.csv", offset = 1_000)
        val restarted = runtime(PresenceMode.AUTOMATIC, touches)
        assertTrue(restarted.isLearnedSignal())
    }

    /**
     * Someone stands at the panel (about 50) and touches it [touches] times, well apart (hand about 600), then
     * leaves: from [StayedThenLeft.leftAt] every reading is a fresh empty-room reading (about 30). No touch and
     * no mode change follows.
     */
    private class StayedThenLeft(val runtime: ProximityCalibrationRuntime, touches: Int) {
        var now = 1_000L
        val leftAt: Long
        val afterLeaving = mutableListOf<Boolean?>()

        init {
            repeat(touches) { hold(50f, 8_000); tap(); hold(50f, 12_000) }
            hold(50f, 6_000)
            leftAt = now
        }

        fun hold(level: Float, ms: Long, fresh: Boolean = true) {
            val end = now + ms
            while (now < end) {
                val near = runtime.observe(level + (now / 250 % 3) - 1, now, sparseReporting = false, calibrationLive = fresh).near
                if (level < 40f) afterLeaving.add(near)
                now += 250
            }
        }

        private fun tap() {
            repeat(2) { runtime.observe(600f, now, sparseReporting = false); now += 250 }
            runtime.touch(now)
            repeat(2) { runtime.observe(600f, now, sparseReporting = false); now += 250 }
        }
    }

    private fun savedClearMean(touches: MemoryTouches): Double? =
        touches.values.values.single().let(::JSONObject).optJSONObject("clear")?.getDouble("mean")

    @Test fun aModelThatQualifiesOnlyOnceThePersonLeavesIsAdoptedAndSavedWithoutAnotherTouch() {
        val touches = MemoryTouches()
        val visit = StayedThenLeft(runtime(PresenceMode.AUTOMATIC, touches), touches = 3)
        val runtime = visit.runtime
        // While they stayed, the empty room had never been seen: three touches alone cannot calibrate.
        assertEquals(null, runtime.touchLearning())
        assertFalse(runtime.isLearnedSignal())
        assertEquals("learning", JSONObject(runtime.statusJson()).getString("state"))

        visit.hold(30f, 60_000)

        assertEquals(30.0, checkNotNull(runtime.touchLearning()).clear.mean, 1.0)
        assertTrue("the running calibration is the learned one", runtime.isLearnedSignal())
        assertEquals("ready", JSONObject(runtime.statusJson()).getString("state"))
        assertEquals("touch", JSONObject(runtime.json()).getString("calibrationSource"))
        assertEquals("the empty room reads as clear", false, visit.afterLeaving.last())
        assertEquals(30.0, checkNotNull(savedClearMean(touches)), 1.0)

        val restarted = runtime(PresenceMode.AUTOMATIC, touches)
        assertTrue("the saved model runs after a restart", restarted.isLearnedSignal())
        assertEquals(30.0, checkNotNull(restarted.touchLearning()).clear.mean, 1.0)
    }

    @Test fun twoTouchesBeforeLeavingStayUncalibratedHoweverLongTheRoomIsEmpty() {
        val touches = MemoryTouches()
        val visit = StayedThenLeft(runtime(PresenceMode.AUTOMATIC, touches), touches = 2)
        visit.hold(30f, 60_000)
        assertEquals(null, visit.runtime.touchLearning())
        assertFalse(visit.runtime.isLearnedSignal())
        assertEquals("learning", JSONObject(visit.runtime.statusJson()).getString("state"))
        assertTrue(visit.afterLeaving.all { it == null })
        // The empty room it saw is still kept, so a third touch after a restart has a clear level to use.
        assertEquals(30.0, checkNotNull(savedClearMean(touches)), 1.0)
        assertFalse(runtime(PresenceMode.AUTOMATIC, touches).isLearnedSignal())
    }

    @Test fun heldReadingsAfterLeavingAreNotEvidenceOfAnEmptyRoom() {
        val touches = MemoryTouches()
        val visit = StayedThenLeft(runtime(PresenceMode.AUTOMATIC, touches), touches = 3)
        val saved = touches.writes
        visit.hold(30f, 60_000, fresh = false)
        assertEquals(null, visit.runtime.touchLearning())
        assertFalse(visit.runtime.isLearnedSignal())
        assertEquals(saved, touches.writes)
    }

    @Test fun anEmptyRoomThatStaysTheSameIsNotSavedAgain() {
        val touches = MemoryTouches()
        val visit = StayedThenLeft(runtime(PresenceMode.AUTOMATIC, touches), touches = 3)
        visit.hold(30f, 60_000)
        assertTrue(visit.runtime.isLearnedSignal())
        val saved = touches.writes
        visit.hold(30f, 120_000)
        assertEquals(saved, touches.writes)
    }

    @Test fun switchingFromManualToAutomaticUsesWhatWasAlreadyLearned() {
        var mode = PresenceMode.MANUAL
        val runtime = ProximityCalibrationRuntime(
            sourceIdentity = "android_hal|test", profileId = "generic", profileBaseline = null, store = MemoryModels(),
            elapsed = { 0L }, wall = { 0L }, presenceMode = { mode }, touchStore = MemoryTouches(),
        )
        runtime.replay("office-taps.csv", offset = 1_000)
        assertFalse(runtime.isLearnedSignal())
        mode = PresenceMode.AUTOMATIC
        runtime.observe(37f, 500_000, sparseReporting = false)
        assertTrue(runtime.isLearnedSignal())
        // Back to Manual with no setup or profile calibration: the touch-learned model must stop running.
        mode = PresenceMode.MANUAL
        val manual = runtime.observe(37f, 500_250, sparseReporting = false)
        assertFalse(runtime.isLearnedSignal())
        assertEquals(null, manual.near)
        runtime.observe(37f, 500_500, sparseReporting = false)
        assertEquals("calibration_required", JSONObject(runtime.statusJson()).getString("state"))
    }
}
