package io.panelassistant.android

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProximityWizardNarratorTest {
    @Test fun unchangedSemanticPromptIsSpokenOnlyOnce() = runTest {
        val spoken = mutableListOf<String>()
        val narrator = ProximityWizardNarrator({ text, _, _ -> spoken += text }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            assertTrue(narrator.narrate("clear|PREPARE", "Stand clear", "en-GB"))
            assertFalse(narrator.narrate("clear|PREPARE", "Stand clear again", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("Stand clear"), spoken)
        } finally { narrator.close() }
    }

    @Test fun nextInstructionWaitsForTheCurrentSentenceAndAQuietGap() = runTest {
        val events = mutableListOf<String>()
        val firstFinished = CompletableDeferred<Unit>()
        val narrator = ProximityWizardNarrator({ text, locale, accepted ->
            events += "start:$text:$locale"
            accepted(if (text == "Get ready") 41L else 42L)
            if (text == "Get ready") firstFinished.await()
        }, stopPlayback = { events += "stop-playback:$it" }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            narrator.narrate("clear", "Get ready", "en-GB")
            testScheduler.runCurrent()
            narrator.narrate("near", "Stand near", "fr-FR")
            testScheduler.runCurrent()
            assertEquals(listOf("start:Get ready:en-GB"), events)

            firstFinished.complete(Unit)
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(449)
            testScheduler.runCurrent()
            assertEquals(listOf("start:Get ready:en-GB"), events)

            testScheduler.advanceTimeBy(1)
            testScheduler.runCurrent()
            assertEquals(listOf("start:Get ready:en-GB", "start:Stand near:fr-FR"), events)
        } finally { narrator.close(); testScheduler.runCurrent() }
    }

    @Test fun stopCancelsSpeechAndAllowsVisibleActivityToRepeatThePrompt() = runTest {
        val spoken = mutableListOf<String>()
        val narrator = ProximityWizardNarrator({ text, _, _ -> spoken += text }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            narrator.narrate("near|APPROACH", "Approach", "en-GB")
            testScheduler.runCurrent()
            narrator.stop()
            assertTrue(narrator.narrate("near|APPROACH", "Approach", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("Approach", "Approach"), spoken)
        } finally { narrator.close() }
    }

    @Test fun speechFailureNeverClosesNarrationOrBlocksTheNextVisualStep() = runTest {
        val attempts = mutableListOf<String>()
        val narrator = ProximityWizardNarrator({ text, _, _ ->
            attempts += text
            if (text == "First") error("Home Assistant unavailable")
        }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            narrator.narrate("clear|PREPARE", "First", "en-GB")
            testScheduler.runCurrent()
            assertTrue(narrator.narrate("clear|WAIT_CLEAR", "Second", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("First", "Second"), attempts)
        } finally { narrator.close() }
    }

    @Test fun closeRejectsLateNarration() = runTest {
        val narrator = ProximityWizardNarrator({ _, _, _ -> }, dispatcher = StandardTestDispatcher(testScheduler))
        narrator.close()
        assertFalse(narrator.narrate("waves|WAVE", "Move your hand closer", "en-GB"))
    }

    @Test fun stopDoesNotCancelUnrelatedAudioBeforeWizardPlaybackStarts() = runTest {
        var stops = 0
        val narrator = ProximityWizardNarrator(
            speak = { _, _, _ -> awaitCancellation() },
            stopPlayback = { stops++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            narrator.narrate("intro|NONE", "Introduction", "en-GB")
            testScheduler.runCurrent()
            narrator.stop()
            testScheduler.runCurrent()
            assertEquals(0, stops)
        } finally { narrator.close() }
    }
}
