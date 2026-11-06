package io.panelassistant.android.assist.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.panelassistant.android.CoreInstrumentation
import io.panelassistant.android.audio.PcmFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the bundled `okay_nabu` model through the real native engine and scores six seconds of
 * silence: the library must load on the device ABI, the model must be accepted, and inference
 * must run once per stride without a hit.
 */
@RunWith(AndroidJUnit4::class)
@CoreInstrumentation
class NativeMicroWakeWordInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun okayNabuScoresSilenceWithoutFiring() {
        assertTrue("native wake-word library must load on the test device", NativeMicroWakeWord.available)
        val catalog = WakeWordCatalog(context)
        val loaded = catalog.load("okay_nabu")
        assertNotNull("engine rejected okay_nabu", loaded)
        val config = loaded!!.config
        (loaded.scorer as NativeMicroWakeWord).use { native ->
            assertEquals(3, native.stride)
            val hits = mutableListOf<WakeWordHit>()
            var detectorInferences = 0
            val scorer = object : WakeWordScorer by native {
                override fun score(samples: ShortArray, count: Int): Int {
                    val probability = native.score(samples, count)
                    if (probability >= 0) detectorInferences++
                    return probability
                }
            }
            val detector = WakeWordDetector(listOf(LoadedWakeWordModel(config, scorer)), { hit -> hits += hit })
            var inferences = 0
            val silence = ShortArray(160)
            // Cross the real detector's warmup before checking silence does not fire.
            repeat(600) { index ->
                detector.onFrame(PcmFrame(silence, timestampNs = index.toLong()))
            }
            assertTrue("silence must reach an armed detector", detectorInferences > WakeWordDetector.DEFAULT_WARMUP_INFERENCES)
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
