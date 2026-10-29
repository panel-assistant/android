package io.panelassistant.android.assist.wakeword

import io.panelassistant.android.assist.MicroWakeWordEngineFactory
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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

    @Test
    fun `a model that fails to load closes the ones already built`() {
        val first = CountingScorer()
        val failure = IllegalStateException("model is corrupt")
        val factory = MicroWakeWordEngineFactory(load = { id ->
            if (id == "hey_jarvis") throw failure
            LoadedWakeWordModel(config(id), first)
        })

        val thrown = runCatching { factory.create(listOf("okay_nabu", "hey_jarvis")) {} }.exceptionOrNull()

        assertSame("the original failure must reach the caller", failure, thrown)
        assertTrue("the model already built must not be left holding its arena", first.closed)
    }

    @Test
    fun `a clean load leaves models open until the returned engine closes`() {
        val scorers = listOf(CountingScorer(), CountingScorer())
        val factory = MicroWakeWordEngineFactory(load = { id ->
            LoadedWakeWordModel(config(id), scorers[if (id == "okay_nabu") 0 else 1])
        })

        val engine = factory.create(listOf("okay_nabu", "hey_jarvis")) {}

        assertNotNull("usable models arm an engine", engine)
        assertTrue("the returned engine owns the still-open models", scorers.none { it.closed })
        engine!!.close()
        assertTrue("closing the returned engine releases every model", scorers.all { it.closed })
    }

    @Test
    fun `a failure while configuring the detector closes every acquired model`() {
        val scorers = listOf(CountingScorer(), CountingScorer())
        val failure = IllegalStateException("settings unavailable")
        val factory = MicroWakeWordEngineFactory(
            load = { id -> LoadedWakeWordModel(config(id), scorers[if (id == "okay_nabu") 0 else 1]) },
            sensitivity = { throw failure },
        )

        val thrown = runCatching { factory.create(listOf("okay_nabu", "hey_jarvis")) {} }.exceptionOrNull()

        assertSame("the original failure must reach the caller", failure, thrown)
        assertTrue("no acquired arena survives a failed engine construction", scorers.all { it.closed })
    }
}
