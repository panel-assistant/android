package io.panelassistant.android.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recorder the audio server has lost must not leave a held lease deaf. The source rebuilds the device on
 * its own timer, backing off from 250 ms to 5 s while rebuilds keep failing and starting again from 250 ms
 * once one reads audio, and every lease that heard the old generation is reset before it hears the new.
 *
 * Driven through the real capture device over a scripted recorder, with the rebuild timer replaced by one
 * the test fires, so every delay the source asks for is observable and nothing waits on a clock.
 */
class MicrophoneFanOutRecoveryTest {

    private val recorders = CopyOnWriteArrayList<FakeRecorder>()
    private val delays = CopyOnWriteArrayList<Long>()
    private val timers = LinkedBlockingQueue<() -> Unit>()
    private val cancelled = AtomicInteger()
    @Volatile private var refuseStarts = 0
    private val fan = MicrophoneFanOut(
        deviceFactory = {
            AudioRecordCaptureDevice({
                FakeRecorder(blockWhenIdle = true).also {
                    if (refuseStarts > 0) {
                        refuseStarts -= 1
                        it.startSucceeds = false
                    }
                    recorders.add(it)
                }
            })
        },
        threadNamePrefix = "test-mic",
        rebuildScheduler = { delayMs, task ->
            delays.add(delayMs)
            timers.put(task)
            AutoCloseable { cancelled.incrementAndGet() }
        },
    )

    @After
    fun shutdown() {
        fan.shutdown(2_000L)
    }

    private class Listener : PcmConsumer {
        val frames = AtomicInteger()
        val resets = AtomicInteger()
        val dropped = AtomicInteger()

        override fun onFrame(frame: PcmFrame) {
            frames.incrementAndGet()
        }

        override fun onDropped(count: Int) {
            dropped.addAndGet(count)
        }

        override fun onReset() {
            resets.incrementAndGet()
        }
    }

    private fun frame(value: Short = 100) = ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { value }

    private fun fireNextTimer() {
        val task = timers.poll(5L, TimeUnit.SECONDS)
        assertNotNull("a rebuild was scheduled", task)
        task!!.invoke()
    }

    @Test fun aDeadObjectRebuildsTheDeviceAndTheHeldWakeLeaseHearsAgainAfterOneReset() {
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)
        awaitTrue("the first recorder starts") { recorders.size == 1 && recorders[0].recording }
        recorders[0].offer(frame())
        awaitTrue("the wake listener hears the first generation") { wake.frames.get() == 1 }

        recorders[0].offerCode(MIC_READ_DEAD_OBJECT)
        awaitTrue("the dead recorder is released") { recorders[0].released.get() == 1 }
        awaitTrue("the source reports the failure while it waits") { fan.state.value is MicState.Error }
        assertEquals("one rebuild is scheduled, after the first backoff step", listOf(250L), delays)

        fireNextTimer()
        awaitTrue("the rebuild builds and starts a new recorder") { recorders.size == 2 && recorders[1].recording }
        recorders[1].offer(frame(200))
        awaitTrue("delivery resumes to the same lease") { wake.frames.get() == 2 }

        assertEquals("the listener was reset exactly once, for the new generation", 1, wake.resets.get())
        assertEquals("a reset is not reported as dropped frames", 0, wake.dropped.get())
        assertTrue("the lease is still the one taken at the start", lease.active)
        assertEquals("the source is open again", listOf(MicPurpose.WAKE_WORD), (fan.state.value as? MicState.Open)?.holders?.map { it.purpose })
        assertEquals("the rebuild is counted", 1, fan.rebuilds)

        val late = Listener()
        fan.lease(MicPurpose.CALIBRATION, consumer = late, queueFrames = 32)
        recorders[1].offer(frame(300))
        awaitTrue("a lease taken after the rebuild hears it") { late.frames.get() == 1 }
        assertEquals("and starts clean, with no reset", 0, late.resets.get())
        lease.close()
    }

    @Test fun rebuildsBackOffWhileTheyFailAndStartAgainFromTheFirstStepOnceAudioFlows() {
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)
        awaitTrue("the first recorder starts") { recorders.size == 1 && recorders[0].recording }
        recorders[0].offer(frame())
        awaitTrue("audio flows") { wake.frames.get() == 1 }

        recorders[0].offerCode(MIC_READ_DEAD_OBJECT)
        awaitTrue("the first rebuild is scheduled") { delays.size == 1 }
        // The audio server is still restarting: the next two recorders refuse to start.
        refuseStarts = 2
        fireNextTimer()
        awaitTrue("a rebuild that cannot open schedules the next") { delays.size == 2 }
        fireNextTimer()
        awaitTrue("and so does the one after it") { delays.size == 3 }
        fireNextTimer()
        awaitTrue("the third rebuild opens") { recorders.size == 4 && recorders[3].recording }
        recorders[3].offer(frame())
        awaitTrue("audio flows again") { wake.frames.get() == 2 }

        recorders[3].offerCode(MIC_READ_DEAD_OBJECT)
        awaitTrue("a later failure is scheduled too") { delays.size == 4 }
        assertEquals("250 ms doubling while it fails, back to 250 ms after audio", listOf(250L, 500L, 1_000L, 250L), delays)
        assertEquals(3, fan.rebuilds)
        lease.close()
    }

    @Test fun otherFailedReadsAreRetriedUpToTheBoundBeforeTheDeviceIsRebuilt() {
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)
        awaitTrue("the first recorder starts") { recorders.size == 1 && recorders[0].recording }

        repeat(MIC_MAX_CONSECUTIVE_READ_ERRORS - 1) { recorders[0].offerCode(-3) }
        recorders[0].offer(frame())
        awaitTrue("audio after a run of transient refusals is delivered") { wake.frames.get() == 1 }
        assertEquals("no rebuild for refusals under the bound", 0, delays.size)
        assertEquals("on the same recorder", 1, recorders.size)

        repeat(MIC_MAX_CONSECUTIVE_READ_ERRORS) { recorders[0].offerCode(-3) }
        awaitTrue("the bound in a row ends the generation and schedules a rebuild") { delays.size == 1 }
        awaitTrue("the recorder is released") { recorders[0].released.get() == 1 }
        lease.close()
    }

    @Test fun releasingTheLastLeaseCancelsAPendingRebuild() {
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = Listener(), queueFrames = 32)
        awaitTrue("the first recorder starts") { recorders.size == 1 && recorders[0].recording }
        recorders[0].offerCode(MIC_READ_DEAD_OBJECT)
        awaitTrue("a rebuild is scheduled") { delays.size == 1 }

        lease.close()
        assertEquals("the pending rebuild is cancelled", 1, cancelled.get())
        fireNextTimer()
        assertEquals("a timer that fires anyway builds nothing", 1, recorders.size)
        assertEquals(MicState.Closed, fan.state.value)
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(1L)
        assertTrue("$what — not satisfied within ${timeoutMs}ms", condition())
    }
}
