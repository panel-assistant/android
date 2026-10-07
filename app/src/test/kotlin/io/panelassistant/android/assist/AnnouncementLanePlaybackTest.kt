package io.panelassistant.android.assist

import io.panelassistant.android.media.AudioPlaybackCoordinator
import io.panelassistant.android.media.AudioPlaybackRun
import io.panelassistant.android.media.AudioPlaybackRunFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnnouncementLanePlaybackTest {
    @Test fun reportsTheExactAcceptedGenerationSeparatelyFromTheStartedSignal() = runTest {
        val coordinator = AudioPlaybackCoordinator(
            AudioPlaybackRunFactory {
                object : AudioPlaybackRun {
                    override suspend fun execute() = Unit
                    override fun cancel() = Unit
                }
            },
            StandardTestDispatcher(testScheduler),
        )
        val generations = mutableListOf<Long>()
        var starts = 0
        val playback = AnnouncementLanePlayback(
            coordinator,
            pollMs = 1L,
            onStarted = { starts++ },
            onGeneration = generations::add,
        )
        val job = launch { playback.play("spoken-instruction") }
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(listOf(1L), generations)
        assertEquals(1, starts)
        assertTrue(coordinator.close(1_000L))
    }

    /** A streamed lane over the real session-side stream, whose player only records mute and unmute. */
    private class StreamedLane(scope: kotlinx.coroutines.test.TestScope) {
        val player = mutableListOf<String>()
        val busy = mutableListOf<Boolean>()
        val stream = io.panelassistant.android.panelassistant.PanelAssistantVoiceStream(
            object : io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamPeer {
                override fun clientId() = "C".repeat(43)
                override fun open(grant: io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamGrant, send: (ByteArray, Boolean) -> Unit) = Unit
                override fun receive(frame: ByteArray, text: Boolean) = Unit
                override fun mute() { player += "mute" }
                override fun unmute() { player += "unmute" }
                override fun close() = Unit
            },
        ).also { it.open("session", io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamGrant("S".repeat(43), ByteArray(32))) }
        val coordinator = AudioPlaybackCoordinator(
            object : AudioPlaybackRunFactory {
                override fun create(url: String) = error("a streamed reply fetches no URL")
                override fun createStream(streamId: String): AudioPlaybackRun = io.panelassistant.android.media.StreamedSpeechRun(stream, streamId)
                override fun streamEnded(streamId: String) = stream.ended(streamId)
            },
            StandardTestDispatcher(scope.testScheduler),
            onBusyChanged = { busy += it },
        )

        fun end(streamId: String, listenAfter: Boolean) {
            stream.onFrame(
                JSONObject().put("id", 1).put("type", "event").put(
                    "event",
                    JSONObject().put("kind", "voice_stream_end").put("stream_id", streamId).put("outcome", "played").put("listen_after", listenAfter),
                ),
                1L,
            )
        }

        fun sent(): List<JSONObject> = generateSequence(2L) { it + 1 }.map { stream.next(it) }.takeWhile { it != null }.map { JSONObject(it!!) }.toList()
    }

    @Test fun aStreamedRunHoldsTheLaneAndSoTheMediaUntilItsEndThenReturnsItsListenAfter() = runTest {
        val lane = StreamedLane(this)
        val playback = AnnouncementLanePlayback(lane.coordinator, pollMs = 1L)
        val reply = async { playback.playStream("s1") }
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertFalse("no download, no timeout: the run waits for Panel Assistant's end", reply.isCompleted)
        assertEquals("the lane, and so the panel's media, is held", listOf(true), lane.busy)
        lane.end("s1", listenAfter = true)
        runCurrent()
        advanceTimeBy(10L)
        assertTrue("the end's listen_after comes back", reply.await())
        assertEquals("released at the end", listOf(true, false), lane.busy)
        assertTrue("an ended run asks Panel Assistant for nothing", lane.sent().isEmpty())

        val quiet = async { playback.playStream("s2") }
        runCurrent()
        lane.end("s2", listenAfter = false)
        runCurrent()
        advanceTimeBy(10L)
        assertFalse(quiet.await())
        assertTrue(lane.coordinator.close(1_000L))
    }

    @Test fun cancellingAStreamedRunMutesAndAsksPanelAssistantToStopThatStream() = runTest {
        val lane = StreamedLane(this)
        val generation = requireNotNull(lane.coordinator.submitStreamForGeneration("s7"))
        runCurrent()
        assertTrue(lane.coordinator.cancelGeneration(generation))
        runCurrent()
        assertEquals(listOf("mute"), lane.player)
        val stop = lane.sent().single()
        assertEquals("panel_assistant/voice_stream_stop", stop.getString("type"))
        assertEquals("s7", stop.getString("stream_id"))
        assertEquals("session", stop.getString("session"))
        assertTrue(lane.coordinator.close(1_000L))
    }
}
