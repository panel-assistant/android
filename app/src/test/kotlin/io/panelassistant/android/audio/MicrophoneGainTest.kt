package io.panelassistant.android.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneGainTest {
    @Test fun zeroDecibelsIsUnityAndLeavesEverySampleAlone() {
        assertEquals(1.0, MicrophoneGain.factorFor(0), 0.0)
        val samples = shortArrayOf(0, 1, -1, 12345, -12345, Short.MAX_VALUE, Short.MIN_VALUE)
        val before = samples.copyOf()
        MicrophoneGain.applyInPlace(samples, samples.size, MicrophoneGain.factorFor(0))
        assertArrayEqualsShort(before, samples)
    }

    @Test fun sixDecibelsIsAboutDoubleAndMinusSixIsAboutHalf() {
        assertEquals(2.0, MicrophoneGain.factorFor(6), 0.01)
        assertEquals(0.5, MicrophoneGain.factorFor(-6), 0.01)
        val samples = shortArrayOf(1000, -1000)
        MicrophoneGain.applyInPlace(samples, samples.size, MicrophoneGain.factorFor(6))
        assertTrue("expected about 2000, got ${samples[0]}", abs(samples[0] - 2000) <= 20)
        assertTrue("expected about -2000, got ${samples[1]}", abs(samples[1] + 2000) <= 20)
    }

    /**
     * The failure this prevents is not a rounding error. An int16 that wraps turns the loudest moment of
     * an utterance into full-scale noise of the opposite sign, so raising your voice to be heard would
     * make transcription worse rather than better.
     */
    @Test fun loudSamplesSaturateAtTheSixteenBitBoundsInsteadOfWrapping() {
        val samples = shortArrayOf(30000, -30000, Short.MAX_VALUE, Short.MIN_VALUE)
        MicrophoneGain.applyInPlace(samples, samples.size, MicrophoneGain.factorFor(MicrophoneGain.MAX_DB))
        assertEquals(Short.MAX_VALUE, samples[0])
        assertEquals(Short.MIN_VALUE, samples[1])
        assertEquals(Short.MAX_VALUE, samples[2])
        assertEquals(Short.MIN_VALUE, samples[3])
    }

    @Test fun gainBeyondTheSupportedRangeIsClampedRatherThanApplied() {
        assertEquals(MicrophoneGain.factorFor(MicrophoneGain.MAX_DB), MicrophoneGain.factorFor(1000), 0.0)
        assertEquals(MicrophoneGain.factorFor(MicrophoneGain.MIN_DB), MicrophoneGain.factorFor(-1000), 0.0)
    }

    @Test fun onlyTheRequestedPrefixOfTheBufferIsScaled() {
        val samples = shortArrayOf(1000, 1000, 1000, 1000)
        MicrophoneGain.applyInPlace(samples, 2, MicrophoneGain.factorFor(6))
        assertTrue("first half should be amplified", samples[0] > 1900)
        assertTrue("first half should be amplified", samples[1] > 1900)
        assertEquals(1000, samples[2].toInt())
        assertEquals(1000, samples[3].toInt())
    }

    @Test fun aCountLargerThanTheBufferDoesNotReadPastItsEnd() {
        val samples = shortArrayOf(1000, 1000)
        MicrophoneGain.applyInPlace(samples, 99, MicrophoneGain.factorFor(6))
        assertTrue(samples[0] > 1900)
        assertTrue(samples[1] > 1900)
    }

    @Test fun theStageAmplifiesTheFrameItForwardsAndRelaysDrops() {
        val seen = mutableListOf<ShortArray>()
        val drops = mutableListOf<Int>()
        val downstream = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) { seen += frame.samples }
            override fun onDropped(count: Int) { drops += count }
        }
        val stage = GainStage(downstream, 6)
        stage.onFrame(PcmFrame(shortArrayOf(1000, -1000), timestampNs = 7L))
        stage.onDropped(3)

        assertEquals(1, seen.size)
        assertTrue("forwarded frame should be amplified, got ${seen[0][0]}", seen[0][0] > 1900)
        assertTrue(seen[0][1] < -1900)
        assertEquals(listOf(3), drops)
    }

    @Test fun theStageAtZeroDecibelsForwardsTheSameUntouchedFrame() {
        var forwarded: PcmFrame? = null
        val stage = GainStage(object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) { forwarded = frame }
        }, 0)
        val frame = PcmFrame(shortArrayOf(1000, -1000), timestampNs = 1L)
        stage.onFrame(frame)
        assertSame(frame, forwarded)
        assertEquals(1000, frame.samples[0].toInt())
        assertEquals(-1000, frame.samples[1].toInt())
    }

    /**
     * Guards the reason the stage exists at all. microWakeWord's own frontend applies PCAN adaptive gain,
     * so the detector needs no help and is hurt by clipping; speech-to-text has no such adaptation. If a
     * future change routes the wake-word listener through a gain stage, the two consumers stop seeing the
     * same signal for the same utterance and this assertion is the one that should fail.
     */
    @Test fun aStageWrapsOnlyTheConsumerItWasGivenAndNotItsSiblings() {
        val wakeWordSaw = mutableListOf<Short>()
        val wakeWord = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) { wakeWordSaw += frame.samples[0] }
        }
        val pipelineSaw = mutableListOf<Short>()
        val pipeline = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) { pipelineSaw += frame.samples[0] }
        }
        val amplified = GainStage(pipeline, 12)

        // The fan-out hands each consumer its own array, which is what makes the two paths independent.
        wakeWord.onFrame(PcmFrame(shortArrayOf(1000), timestampNs = 1L))
        amplified.onFrame(PcmFrame(shortArrayOf(1000), timestampNs = 1L))

        assertEquals(1000, wakeWordSaw.single().toInt())
        assertNotSame(wakeWordSaw.single(), pipelineSaw.single())
        assertTrue("pipeline should be louder, got ${pipelineSaw.single()}", pipelineSaw.single() > 3000)
    }

    private fun assertArrayEqualsShort(expected: ShortArray, actual: ShortArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("index $i", expected[i], actual[i])
    }
}
