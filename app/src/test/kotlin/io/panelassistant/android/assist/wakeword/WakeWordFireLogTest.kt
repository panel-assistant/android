package io.panelassistant.android.assist.wakeword

import io.panelassistant.android.assist.MicroWakeWordEngineFactory
import io.panelassistant.android.assist.WakeWordActivation
import io.panelassistant.android.audio.MicrophoneSource
import io.panelassistant.android.audio.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A release build's log is the only record of how well a panel hears its wake word, and the after-run scorer
 * reads it: every wake that fires says its score against its cutoff, and every near miss keeps its old text,
 * each with the level the listener received over the last 1.5 s.
 */
class WakeWordFireLogTest {

    /** Scores whatever the test sets, one inference per frame. */
    private class ScriptedScorer : WakeWordScorer {
        var next = 0
        override fun score(samples: ShortArray, count: Int) = next
        override fun reset() {}
        override fun close() {}
    }

    private fun config(cutoff: Float) = MicroWakeWordModelConfig(
        id = "okay_nabu",
        wakeWord = "okay nabu",
        author = "test",
        website = null,
        modelFile = "okay_nabu.tflite",
        trainedLanguages = listOf("en"),
        version = 2,
        probabilityCutoff = cutoff,
        featureStepSizeMs = 10,
        slidingWindowSize = 4,
        tensorArenaSize = 0,
    )

    private val logs = mutableListOf<String>()
    private val activations = mutableListOf<WakeWordActivation>()
    private val scorer = ScriptedScorer()

    private fun engine(cutoff: Float) = requireNotNull(
        MicroWakeWordEngineFactory(load = { LoadedWakeWordModel(config(cutoff), scorer) }, log = { logs += it })
            .create(listOf("okay_nabu")) { activations += it },
    )

    /** One 10 ms frame at a steady [amplitude], scored [score]. */
    private fun io.panelassistant.android.assist.WakeWordEngine.hear(frames: Int, score: Int, amplitude: Short) {
        scorer.next = score
        repeat(frames) { onFrame(PcmFrame(ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { amplitude }, timestampNs = 0L)) }
    }

    @Test
    fun `a wake that fires logs its score, its cutoff and the level heard, and carries the score`() {
        val engine = engine(cutoff = 0.9f)
        // Past the detector's warm-up, at a tenth of full scale: -20 dBFS.
        engine.hear(frames = 200, score = 0, amplitude = 3_277)
        engine.hear(frames = 4, score = 255, amplitude = 3_277)

        assertEquals(
            listOf("wake word okay_nabu fired at 1.00 (cutoff 0.90), level rms -20.0 dBFS peak -20.0 dBFS clipped 0"),
            logs,
        )
        val fired = activations.single()
        assertNotNull(fired.probability)
        assertEquals(1.0f, fired.probability!!, 0.001f)
        assertEquals(0.9f, fired.cutoff!!, 0.001f)
    }

    @Test
    fun `a near miss keeps its line and adds the level, clipping included`() {
        val engine = engine(cutoff = 0.9f)
        engine.hear(frames = 200, score = 0, amplitude = 100)
        // Shouted into the panel: every sample at full scale while the score climbs short of the cutoff.
        engine.hear(frames = 4, score = 160, amplitude = Short.MAX_VALUE)
        engine.hear(frames = 4, score = 0, amplitude = Short.MAX_VALUE)

        assertEquals(1, logs.size)
        val line = logs.single()
        assertEquals("wake word okay_nabu heard at 0.63, short of its cutoff", line.substringBefore(", level"))
        // The miss is reported on the first quiet frame, after five loud frames of 160 samples each.
        assertEquals("level rms -14.8 dBFS peak -0.0 dBFS clipped 800", line.substringAfter("short of its cutoff, "))
        assertEquals(emptyList<WakeWordActivation>(), activations)
    }
}
