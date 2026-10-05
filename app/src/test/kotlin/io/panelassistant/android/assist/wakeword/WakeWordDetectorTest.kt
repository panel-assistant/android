package io.panelassistant.android.assist.wakeword

import io.panelassistant.android.audio.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {
    /** Scripted scorer: returns the next queued probability per call, -1 once the script is exhausted. */
    private class FakeScorer : WakeWordScorer {
        val script = ArrayDeque<Int>()
        var calls = 0
        var resets = 0
        var closed = false

        override fun score(samples: ShortArray, count: Int): Int {
            calls++
            return script.removeFirstOrNull() ?: WakeWordScorer.NO_INFERENCE
        }

        override fun reset() {
            resets++
        }

        override fun close() {
            closed = true
        }
    }

    private fun config(id: String, cutoff: Float = 0.5f, window: Int = 4) = MicroWakeWordModelConfig(
        id = id,
        wakeWord = id.replace('_', ' '),
        author = "test",
        website = null,
        modelFile = "$id.tflite",
        trainedLanguages = listOf("en"),
        version = 2,
        probabilityCutoff = cutoff,
        featureStepSizeMs = 10,
        slidingWindowSize = window,
        tensorArenaSize = 0,
    )

    private class Rig(
        vararg configs: MicroWakeWordModelConfig,
        cooldown: Int = 200,
        warmup: Int = 0,
        maxActive: Int = 2,
    ) {
        val scorers = configs.map { FakeScorer() }
        val hits = mutableListOf<WakeWordHit>()
        val detector = WakeWordDetector(
            models = configs.mapIndexed { i, c -> LoadedWakeWordModel(c, scorers[i]) },
            listener = { hits += it },
            maxActive = maxActive,
            cooldownChunks = cooldown,
            warmupInferences = warmup,
        )
        var chunk = 0L

        /** Deliver one 10 ms frame; its timestamp is the chunk index in ns so hits are traceable. */
        fun frame(): Long {
            val ts = chunk++
            detector.onFrame(PcmFrame(ShortArray(160), timestampNs = ts))
            return ts
        }

        fun frames(n: Int) = repeat(n) { frame() }
    }

    @Test
    fun windowMeanBelowCutoffNeverFires() {
        val rig = Rig(config("okay_nabu", cutoff = 0.5f, window = 4))
        // mean of 4 window = (255+0+0+0)/4/255 = 0.25 < 0.5
        rig.scorers[0].script.addAll(listOf(255, 0, 0, 0, 255, 0, 0, 0))
        rig.frames(8)
        assertEquals(0, rig.hits.size)
        assertEquals(8, rig.scorers[0].calls)
    }

    @Test
    fun windowMeanExactlyAtCutoffFires() {
        // cutoff 0.5 with window 4: 255+255+0+0 -> mean 0.5 exactly; boundary is inclusive.
        val rig = Rig(config("okay_nabu", cutoff = 0.5f, window = 4))
        rig.scorers[0].script.addAll(listOf(0, 0, 255, 255))
        rig.frames(4)
        assertEquals(1, rig.hits.size)
        assertEquals("okay_nabu", rig.hits[0].modelId)
        assertEquals("okay nabu", rig.hits[0].phrase)
        assertEquals(0.5f, rig.hits[0].probability, 1e-6f)
        assertEquals(3L, rig.hits[0].timestampNs)
    }

    @Test
    fun windowMeanJustBelowCutoffDoesNotFire() {
        val rig = Rig(config("okay_nabu", cutoff = 0.5f, window = 4))
        rig.scorers[0].script.addAll(listOf(0, 0, 255, 254))
        rig.frames(4)
        assertEquals(0, rig.hits.size)
    }

    @Test
    fun noInferenceChunksLeaveTheWindowUntouched() {
        val rig = Rig(config("okay_nabu", cutoff = 0.6f, window = 2))
        // 255 then two stride-gap chunks then 255: the window must still see [255, 255].
        rig.scorers[0].script.addAll(listOf(255, -1, -1, 255))
        rig.frames(4)
        assertEquals(listOf(3L), rig.hits.map { it.timestampNs })
        assertEquals(1.0f, rig.hits[0].probability, 1e-6f)
    }

    @Test
    fun hitClearsTheWindowAndCooldownSuppressesRefireForExactlyCooldownChunks() {
        val rig = Rig(config("okay_nabu", cutoff = 0.6f, window = 2), cooldown = 10)
        // Always-high scorer.
        repeat(100) { rig.scorers[0].script.add(255) }
        rig.frames(2)
        assertEquals(1, rig.hits.size)
        assertEquals(1L, rig.hits[0].timestampNs)
        // Chunks 2..11 are the cooldown (10 chunks). Scoring continues meanwhile, so the window
        // cleared at the hit is full of high values again by chunk 12, the first chunk allowed to
        // fire, and it fires there exactly; then 23, and so on.
        rig.frames(30)
        assertEquals(listOf(1L, 12L, 23L), rig.hits.map { it.timestampNs })
    }

    @Test
    fun cooldownOfZeroAllowsBackToBackHitsAndEachHitClearsTheWindow() {
        val rig = Rig(config("okay_nabu", cutoff = 0.6f, window = 2), cooldown = 0)
        rig.scorers[0].script.addAll(listOf(255, 255, 255, 255, 255, 255))
        rig.frames(6)
        // [255,255] fires at chunk 1 and empties the window; chunk 2 is [255,0] = 0.5 < 0.6, chunk 3
        // refills to [255,255] and fires again, and so on every second chunk.
        assertEquals(listOf(1L, 3L, 5L), rig.hits.map { it.timestampNs })
    }

    @Test
    fun warmupSuppressesTheFirstInferencesThenArms() {
        val rig = Rig(config("okay_nabu", cutoff = 0.5f, window = 1), cooldown = 0, warmup = 5)
        repeat(8) { rig.scorers[0].script.add(255) }
        rig.frames(8)
        // Inferences 1..5 are warm-up; 6, 7, 8 fire.
        assertEquals(listOf(5L, 6L, 7L), rig.hits.map { it.timestampNs })
    }

    @Test
    fun warmupCountsInferencesNotChunks() {
        val rig = Rig(config("okay_nabu", cutoff = 0.5f, window = 1), cooldown = 0, warmup = 2)
        // Stride-3 behaviour: only every third chunk infers.
        rig.scorers[0].script.addAll(listOf(-1, -1, 255, -1, -1, 255, -1, -1, 255))
        rig.frames(9)
        assertEquals(listOf(8L), rig.hits.map { it.timestampNs })
    }

    @Test
    fun droppedFramesResetEveryScorerAndRearmWarmup() {
        val rig = Rig(config("a", cutoff = 0.5f, window = 2), config("b", cutoff = 0.5f, window = 2), cooldown = 0, warmup = 3)
        rig.scorers.forEach { s -> repeat(20) { s.script.add(255) } }
        rig.frames(5)
        assertEquals(listOf("a", "b", "a", "b"), rig.hits.map { it.modelId })
        rig.hits.clear()

        rig.detector.onDropped(7)
        assertEquals(1, rig.scorers[0].resets)
        assertEquals(1, rig.scorers[1].resets)
        // Warm-up applies again: 3 inferences of silence before the 4th can fire.
        rig.frames(3)
        assertEquals(0, rig.hits.size)
        rig.frame()
        assertEquals(listOf("a", "b"), rig.hits.map { it.modelId })
    }

    @Test
    fun droppedFramesClearTheWindow() {
        // Window 4 at cutoff 0.6 needs three high inferences (0.75).
        val rig = Rig(config("okay_nabu", cutoff = 0.6f, window = 4), cooldown = 0)
        rig.scorers[0].script.addAll(listOf(255, 255, 255))
        rig.frames(3)
        assertEquals(listOf(2L), rig.hits.map { it.timestampNs })
        rig.hits.clear()
        // Two more highs after the hit's clear: [255, 255, 0, 0] = 0.5, no hit.
        rig.scorers[0].script.addAll(listOf(255, 255))
        rig.frames(2)
        assertEquals(0, rig.hits.size)
        rig.detector.onDropped(1)
        // Had the drop kept the window, these two would complete four highs and fire.
        rig.scorers[0].script.addAll(listOf(255, 255))
        rig.frames(2)
        assertEquals(0, rig.hits.size)
        // The window is live after the drop: a third high fires.
        rig.scorers[0].script.add(255)
        rig.frame()
        assertEquals(listOf(7L), rig.hits.map { it.timestampNs })
    }

    @Test
    fun twoModelsScoreIndependentlyAndCooldownIsPerModel() {
        val rig = Rig(config("a", cutoff = 0.5f, window = 1), config("b", cutoff = 0.9f, window = 1), cooldown = 5)
        rig.scorers[0].script.addAll(listOf(255, 255, 255, 255, 255, 255, 255, 255))
        rig.scorers[1].script.addAll(listOf(100, 100, 100, 255, 100, 100, 100, 100))
        rig.frames(8)
        // a fires at chunk 0 then cools for 5 chunks (1..5) and fires again at 6; b fires once, at chunk 3.
        assertEquals(listOf("a" to 0L, "b" to 3L, "a" to 6L), rig.hits.map { it.modelId to it.timestampNs })
        assertEquals(8, rig.scorers[0].calls)
        assertEquals(8, rig.scorers[1].calls)
    }

    @Test
    fun everyLoadedModelIsScoredOnEveryFrame() {
        val rig = Rig(config("a"), config("b"))
        rig.frames(3)
        assertEquals(3, rig.scorers[0].calls)
        assertEquals(3, rig.scorers[1].calls)
        assertEquals(listOf("a", "b"), rig.detector.modelIds)
    }

    @Test
    fun moreModelsThanMaxActiveIsRejected() {
        val thrown = runCatching { Rig(config("a"), config("b"), config("c"), maxActive = 2) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun closeClosesEveryScorer() {
        val rig = Rig(config("a"), config("b"))
        rig.detector.close()
        assertTrue(rig.scorers.all { it.closed })
    }

    @Test
    fun higherSensitivityWakesBelowTheModelsOwnCutoffAndLowerDoesNot() {
        fun hitsAt(offset: Float): Int {
            val scorer = FakeScorer()
            val hits = mutableListOf<WakeWordHit>()
            val detector = WakeWordDetector(
                models = listOf(LoadedWakeWordModel(config("hey_jarvis", cutoff = 0.97f, window = 1), scorer)),
                listener = { hits += it },
                warmupInferences = 0,
                cutoffOffset = offset,
            )
            // 230 / 255 = 0.90: short of 0.97, over 0.85.
            scorer.script.add(230)
            detector.onFrame(PcmFrame(ShortArray(160), timestampNs = 0L))
            return hits.size
        }
        assertEquals(0, hitsAt(WakeWordDetector.cutoffOffset("normal")))
        assertEquals(1, hitsAt(WakeWordDetector.cutoffOffset("high")))
        assertEquals(0, hitsAt(WakeWordDetector.cutoffOffset("low")))
    }

    @Test
    fun aNearMissReportsTheHighestMeanOfTheApproachOnceItIsOver() {
        val scorer = FakeScorer()
        val misses = mutableListOf<Pair<String, Float>>()
        val detector = WakeWordDetector(
            models = listOf(LoadedWakeWordModel(config("hey_jarvis", cutoff = 0.97f, window = 1), scorer)),
            listener = {},
            warmupInferences = 0,
            nearMiss = { id, mean -> misses += id to mean },
        )
        // 0.55, 0.80, 0.60 approach the cutoff, then 0.10 ends the approach; a second approach peaks at 0.70.
        scorer.script.addAll(listOf(140, 204, 153, 26, 179, 26))
        repeat(6) { detector.onFrame(PcmFrame(ShortArray(160), timestampNs = it.toLong())) }
        assertEquals(listOf("hey_jarvis", "hey_jarvis"), misses.map { it.first })
        assertEquals(204 / 255f, misses[0].second, 0.001f)
        assertEquals(179 / 255f, misses[1].second, 0.001f)
    }
}
