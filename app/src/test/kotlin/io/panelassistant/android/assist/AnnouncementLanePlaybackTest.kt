package io.panelassistant.android.assist

import io.panelassistant.android.media.AudioPlaybackCoordinator
import io.panelassistant.android.media.AudioPlaybackRun
import io.panelassistant.android.media.AudioPlaybackRunFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
