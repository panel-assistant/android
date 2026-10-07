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

    @Test fun aStreamThatStartedJustBeforeTheEventIsClaimedAndTheRunCompletesWhenItHasPlayedOut() = runTest {
        val (coordinator, claims) = lane()
        val stream = claims.started()
        nowNs += 2 * SECOND
        val generation = requireNotNull(coordinator.submitStreamForGeneration(nowNs))
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        claims.ended(stream)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        assertFalse(claims.dropped(stream))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun anOlderStreamIsNotTheEventsAndTheRunWaitsForTheNextOne() = runTest {
        val (coordinator, claims) = lane()
        val old = claims.started()
        nowNs += 2 * SECOND + 1
        coordinator.submitStreamForGeneration(nowNs)
        runCurrent()
        claims.ended(old)
        runCurrent()
        assertEquals("an older stream ending does not finish this announcement", AudioPlaybackCoordinator.State.ACTIVE, coordinator.snapshot().state)
        val next = claims.started()
        runCurrent()
        claims.ended(next)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.State.IDLE, coordinator.snapshot().state)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun cancellingTheAnnouncementDropsItsStream() = runTest {
        val (coordinator, claims) = lane()
        val generation = requireNotNull(coordinator.submitStreamForGeneration(nowNs))
        runCurrent()
        val stream = claims.started()
        runCurrent()
        assertFalse(claims.dropped(stream))
        assertTrue(coordinator.cancelGeneration(generation))
        runCurrent()
        assertTrue("the player discards the rest of a cancelled announcement", claims.dropped(stream))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aNewerAnnouncementPreemptsTheStreamAndClaimsTheNextOne() = runTest {
        val (coordinator, claims) = lane()
        coordinator.submitStreamForGeneration(nowNs)
        runCurrent()
        val first = claims.started()
        runCurrent()
        nowNs += 3 * SECOND
        val newer = requireNotNull(coordinator.submitStreamForGeneration(nowNs))
        runCurrent()
        assertTrue(claims.dropped(first))
        val second = claims.started()
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
        coordinator.submitStreamForGeneration(nowNs)
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
            override fun createStream(eventAtNs: Long, fallbackUrls: List<String>): AudioPlaybackRun =
                StreamedSpeechRun(claims, eventAtNs, fallbackUrls, ::createSpeech)
        }
        return AudioPlaybackCoordinator(factory, StandardTestDispatcher(testScheduler)) to claims
    }

    @Test fun withNoStreamWithinThreeSecondsTheEventsUrlsPlayInsteadAndALateStreamIsDropped() = runTest {
        val played = mutableListOf<String>()
        val (coordinator, claims) = fallbackLane(played)
        val generation = requireNotNull(coordinator.submitStreamForGeneration(nowNs, listOf("chime.mp3", "message.mp3")))
        runCurrent()
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals("a stream may still start within three seconds", emptyList<String>(), played)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(listOf("chime.mp3", "message.mp3"), played)
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, generation), coordinator.snapshot())
        val late = claims.started()
        runCurrent()
        assertTrue("the fallen-back announcement's late stream never plays over its URL speech", claims.dropped(late))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamArrivingAfterItsAnnouncementWasCancelledIsDropped() = runTest {
        val (coordinator, claims) = lane()
        val generation = requireNotNull(coordinator.submitStreamForGeneration(nowNs))
        runCurrent()
        assertTrue(coordinator.cancelGeneration(generation))
        runCurrent()
        val late = claims.started()
        runCurrent()
        assertTrue("a cancelled announcement's stream is discarded when it arrives", claims.dropped(late))
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun afterAFallbackWhoseStreamNeverCameTheNextAnnouncementsStreamPlays() = runTest {
        val played = mutableListOf<String>()
        val (coordinator, claims) = fallbackLane(played)
        coordinator.submitStreamForGeneration(nowNs, listOf("message.mp3"))
        runCurrent()
        advanceTimeBy(3_001L)
        runCurrent()
        assertEquals(listOf("message.mp3"), played)
        nowNs += 10 * SECOND
        val next = requireNotNull(coordinator.submitStreamForGeneration(nowNs, listOf("other.mp3")))
        runCurrent()
        val stream = claims.started()
        runCurrent()
        assertFalse("the newer announcement owns the next stream", claims.dropped(stream))
        claims.ended(stream)
        runCurrent()
        assertEquals(AudioPlaybackCoordinator.Snapshot(AudioPlaybackCoordinator.State.IDLE, next), coordinator.snapshot())
        assertEquals(listOf("message.mp3"), played)
        assertTrue(coordinator.close(1_000L))
    }

    @Test fun aStreamNoAnnouncementClaimsIsDroppedOnceNoEventCanStillClaimIt() {
        val claims = VoiceStreamClaims(nanoTime = { nowNs })
        val stream = claims.started()
        nowNs += 3 * SECOND
        assertFalse("an event may still arrive for it", claims.dropped(stream))
        nowNs += 1
        assertTrue(claims.dropped(stream))
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
