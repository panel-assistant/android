package io.panelassistant.android.media

import io.panelassistant.android.AudioPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A streamed announcement as the announcement lane runs it: the production run factory and the real
 * stream claims, with the test standing in for the voice player (it says when streams start and end).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamedSpeechPlaybackTest {
    @get:Rule val temp = TemporaryFolder()

    private var nowNs = 100 * SECOND

    private fun TestScope.lane(): Pair<AudioPlaybackCoordinator, VoiceStreamClaims> {
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val coordinator = AudioPlaybackCoordinator(
            AudioPlayer.factory(temp.newFolder(), claims),
            StandardTestDispatcher(testScheduler),
        )
        return coordinator to claims
    }

    /** A stream starts on the player and its first chunk carries server time [startUs]. */
    private fun VoiceStreamClaims.stream(startUs: Long): Long = started().also { firstChunk(it, startUs) }

    private fun cue(startUs: Long?) = StreamCue(nowNs, startUs)

    @Test fun theStreamWhoseFirstChunkCarriesTheEventsStartIsClaimedAndTheRunCompletesWhenItHasPlayedOut() = runTest {
        val (coordinator, claims) = lane()
        val generation = requireNotNull(coordinator.submitStreamForGeneration(cue(A_US)))
        runCurrent()
        val stream = claims.stream(A_US)
        runCurrent()
        assertEquals(VoiceStreamClaims.Ownership.OWNED, claims.ownership(stream))
        claims.ended(stream)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        assertFalse(claims.dropped(stream))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamThatStartedBeforeItsEventIsClaimedWhenTheEventArrives() = runTest {
        val (coordinator, claims) = lane()
        val stream = claims.stream(A_US)
        assertEquals("silent until an event names it", VoiceStreamClaims.Ownership.PENDING, claims.ownership(stream))
        val generation = requireNotNull(coordinator.submitStreamForGeneration(cue(A_US)))
        runCurrent()
        assertEquals(VoiceStreamClaims.Ownership.OWNED, claims.ownership(stream))
        claims.ended(stream)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamCarryingAnotherStartIsNotTheEventsAndTheRunWaitsForItsOwn() = runTest {
        val (coordinator, claims) = lane()
        coordinator.submitStreamForGeneration(cue(B_US))
        runCurrent()
        val other = claims.stream(A_US)
        runCurrent()
        assertEquals(VoiceStreamClaims.Ownership.PENDING, claims.ownership(other))
        claims.ended(other)
        runCurrent()
        assertEquals("another stream ending does not finish this announcement", AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        val own = claims.stream(B_US)
        runCurrent()
        claims.ended(own)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.State.IDLE, coordinator.snapshot().state)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun cancellingTheAnnouncementDropsItsStream() = runTest {
        val (coordinator, claims) = lane()
        val generation = requireNotNull(coordinator.submitStreamForGeneration(cue(A_US)))
        runCurrent()
        val stream = claims.stream(A_US)
        runCurrent()
        assertFalse(claims.dropped(stream))
        assertTrue(coordinator.cancelGeneration(generation))
        runCurrent()
        assertTrue("the player discards the rest of a cancelled announcement", claims.dropped(stream))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aNewerAnnouncementPreemptsTheStreamAndClaimsItsOwn() = runTest {
        val (coordinator, claims) = lane()
        coordinator.submitStreamForGeneration(cue(A_US))
        runCurrent()
        val first = claims.stream(A_US)
        runCurrent()
        nowNs += SECOND
        val newer = requireNotNull(coordinator.submitStreamForGeneration(cue(B_US)))
        runCurrent()
        assertTrue(claims.dropped(first))
        val second = claims.stream(B_US)
        runCurrent()
        assertFalse(claims.dropped(second))
        claims.ended(first)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        claims.ended(second)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, newer), coordinator.snapshot())
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamThatNeverStartsFailsTheAnnouncement() = runTest {
        val (coordinator, _) = lane()
        coordinator.submitStreamForGeneration(cue(A_US))
        runCurrent()
        advanceTimeBy(10_001L)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.State.FAILED, coordinator.snapshot().state)
        assertTrue(coordinator.close(1_000L))
    }

    /** A lane whose URL fallback records what it plays into [played]. */
    private fun TestScope.fallbackLane(played: MutableList<String>): Pair<AudioPlaybackCoordinator, VoiceStreamClaims> {
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val factory = object : AudioPlaybackRunFactory {
            override fun create(url: String) = error("streamed speech never plays as media")
            override fun createSpeech(url: String) = object : AudioPlaybackRun {
                override suspend fun execute() { played += url }
                override fun cancel() {}
            }
            override fun createStream(cue: StreamCue, fallbackUrls: List<String>): AudioPlaybackRun =
                StreamedSpeechRun(claims, cue, fallbackUrls, ::createSpeech)
        }
        return AudioPlaybackCoordinator(factory, StandardTestDispatcher(testScheduler)) to claims
    }

    @Test fun withNoStreamWithinThreeSecondsTheEventsUrlsPlayInsteadAndItsLateStreamIsDropped() = runTest {
        val played = mutableListOf<String>()
        val (coordinator, claims) = fallbackLane(played)
        val generation = requireNotNull(coordinator.submitStreamForGeneration(cue(A_US), listOf("chime.mp3", "message.mp3")))
        runCurrent()
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals("a stream may still start within three seconds", emptyList<String>(), played)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf("chime.mp3", "message.mp3"), played)
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        val late = claims.stream(A_US)
        runCurrent()
        assertTrue("the fallen-back announcement's late stream never plays over its URL speech", claims.dropped(late))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamArrivingAfterItsAnnouncementWasCancelledIsDropped() = runTest {
        val (coordinator, claims) = lane()
        val generation = requireNotNull(coordinator.submitStreamForGeneration(cue(A_US)))
        runCurrent()
        assertTrue(coordinator.cancelGeneration(generation))
        runCurrent()
        val late = claims.stream(A_US)
        runCurrent()
        assertTrue("a cancelled announcement's stream is discarded when it arrives", claims.dropped(late))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun underRapidPreemptionTheCancelledAnnouncementsDelayedStreamIsDroppedAndTheNewerPlaysItsOwn() = runTest {
        val played = mutableListOf<String>()
        val (coordinator, claims) = fallbackLane(played)
        coordinator.submitStreamForGeneration(cue(A_US), listOf("a.mp3"))
        runCurrent()
        nowNs += 600_000_000L
        val b = requireNotNull(coordinator.submitStreamForGeneration(cue(B_US), listOf("b.mp3")))
        runCurrent()
        val lateA = claims.stream(A_US)
        runCurrent()
        assertTrue("A's delayed stream is A's, never B's", claims.dropped(lateA))
        val ownB = claims.stream(B_US)
        runCurrent()
        assertEquals(VoiceStreamClaims.Ownership.OWNED, claims.ownership(ownB))
        claims.ended(lateA)
        runCurrent()
        assertEquals("B does not finish on A's stream", AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        claims.ended(ownB)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, b), coordinator.snapshot())
        assertEquals("neither fell back", emptyList<String>(), played)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun anEventWithoutAStreamStartPlaysItsUrlsAndLeavesTheStreamUnowned() = runTest {
        val played = mutableListOf<String>()
        val (coordinator, claims) = fallbackLane(played)
        val generation = requireNotNull(coordinator.submitStreamForGeneration(cue(null), listOf("message.mp3")))
        runCurrent()
        assertEquals("no wait: an older Panel Assistant names no stream", listOf("message.mp3"), played)
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        val stream = claims.stream(A_US)
        runCurrent()
        assertEquals(VoiceStreamClaims.Ownership.PENDING, claims.ownership(stream))
        assertTrue(coordinator.close(1_000L))
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        const val A_US = 1_759_830_000_500_000L
        const val B_US = 1_759_830_001_100_000L
    }
}
