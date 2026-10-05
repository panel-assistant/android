package io.panelassistant.android.assist.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loading several models is partly done work until the last one succeeds. A model that fails midway
 * leaves the ones already built holding native arenas that nothing else will ever close, because the
 * caller never receives the list.
 */
class WakeWordLoadFailureTest {

    private class CountingScorer : WakeWordScorer {
        var closed = false
        override fun score(samples: ShortArray, count: Int) = WakeWordScorer.NO_INFERENCE
        override fun reset() {}
        override fun close() { closed = true }
    }

    private fun config(id: String) = MicroWakeWordModelConfig(
        id = id,
        wakeWord = id.replace('_', ' '),
        author = "test",
        website = null,
        modelFile = "$id.tflite",
        trainedLanguages = listOf("en"),
        version = 2,
        probabilityCutoff = 0.5f,
        featureStepSizeMs = 10,
        slidingWindowSize = 4,
        tensorArenaSize = 0,
    )

    /**
     * Mirrors the production loop: build each model in turn, and on any failure close whatever was
     * already built before letting the failure out.
     */
    private fun loadOrClose(ids: List<String>, build: (String) -> WakeWordScorer): List<LoadedWakeWordModel> {
        val loaded = ArrayList<LoadedWakeWordModel>()
        try {
            for (id in ids) loaded += LoadedWakeWordModel(config(id), build(id))
        } catch (t: Throwable) {
            loaded.forEach { runCatching { it.close() } }
            throw t
        }
        return loaded
    }

    @Test
    fun `a model that fails to load closes the ones already built`() {
        val first = CountingScorer()
        var thrown: Throwable? = null
        try {
            loadOrClose(listOf("okay_nabu", "hey_jarvis")) { id ->
                if (id == "hey_jarvis") throw IllegalStateException("model is corrupt") else first
            }
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("the failure must reach the caller", thrown is IllegalStateException)
        assertTrue("the model already built must not be left holding its arena", first.closed)
    }

    @Test
    fun `a clean load closes nothing`() {
        val scorers = listOf(CountingScorer(), CountingScorer())
        val loaded = loadOrClose(listOf("okay_nabu", "hey_jarvis")) { id ->
            scorers[if (id == "okay_nabu") 0 else 1]
        }
        assertEquals(2, loaded.size)
        assertTrue("nothing may be closed on the success path", scorers.none { it.closed })
    }
}
