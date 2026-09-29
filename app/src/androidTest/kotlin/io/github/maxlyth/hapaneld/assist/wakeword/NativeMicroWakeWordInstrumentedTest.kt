package io.github.maxlyth.hapaneld.assist.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxlyth.hapaneld.audio.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the bundled `okay_nabu` model through the real native engine and scores three seconds of
 * silence: the library must load on the device ABI, the model must be accepted, and inference
 * must run once per stride without a hit.
 */
@RunWith(AndroidJUnit4::class)
class NativeMicroWakeWordInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun okayNabuScoresSilenceWithoutFiring() {
        assertTrue("native wake-word library must load on the test device", NativeMicroWakeWord.available)
        val catalog = WakeWordCatalog(context)
        val loaded = catalog.load("okay_nabu")
        assertNotNull("engine rejected okay_nabu", loaded)
        val config = loaded!!.config
        (loaded.scorer as NativeMicroWakeWord).use {
            assertEquals(3, it.stride)
            val hits = mutableListOf<WakeWordHit>()
            val detector = WakeWordDetector(listOf(LoadedWakeWordModel(config, it)), { hit -> hits += hit })
            var inferences = 0
            val silence = ShortArray(160)
            repeat(300) { index ->
                // Score through the scorer and the detector in lock-step is impossible (the stream is
                // stateful), so count inferences from a second engine fed the same silence.
                detector.onFrame(PcmFrame(silence, timestampNs = index.toLong()))
            }
            assertEquals(0, hits.size)
            catalog.load("okay_nabu")!!.scorer.use { probe ->
                repeat(300) { if (probe.score(silence) >= 0) inferences++ }
                // 300 chunks = 298 feature frames after the 30 ms window fills; stride 3 -> 99 inferences.
                assertEquals(99, inferences)
                probe.reset()
                var afterReset = 0
                repeat(12) { if (probe.score(silence) >= 0) afterReset++ }
                assertTrue("reset must leave the engine scoring", afterReset > 0)
            }
        }
    }
}
