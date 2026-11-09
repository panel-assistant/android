package io.panelassistant.android.audio

import android.media.MediaRecorder
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
 * A route the hardware builds but will not run must never leave the panel deaf where the canonical
 * `VOICE_RECOGNITION` 16 kHz mono route works: a refused start falls back to it at once, and a route whose
 * generations keep failing before any audio is given up for the canonical one.
 *
 * Driven through the shared fan-out and the real capture devices over scripted recorders, with the rebuild
 * timer replaced by one the test fires.
 */
class CaptureRouteFallbackTest {

    private val stereo = CaptureRoute(channels = 2, downmix = CaptureDownmix.DOMINANCE, warmupReads = 0)
    private val stereoFormat = RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, 2)

    private val requestedRecorders = CopyOnWriteArrayList<FakeRecorder>()
    private val canonicalRecorders = CopyOnWriteArrayList<FakeRecorder>()
    private val timers = LinkedBlockingQueue<() -> Unit>()
    private val sources = mutableListOf<MicrophoneFanOut>()

    /** What each requested recorder does; set before the lease. */
    @Volatile private var requestedStarts = true
    @Volatile private var requestedReadCode = 0

    @After
    fun shutdown() {
        sources.forEach { runCatching { it.shutdown(2_000L) } }
    }

    private fun source(route: CaptureRoute): MicrophoneFanOut = MicrophoneFanOut(
        deviceFactory = FallbackCaptureDevices(route, build = { chosen ->
            if (chosen == CaptureRoute()) {
                AudioRecordCaptureDevice({ FakeRecorder(blockWhenIdle = true).also(canonicalRecorders::add) })
            } else {
                ConvertingCaptureDevice(
                    AudioRecordCaptureDevice({
                        FakeRecorder(stereoFormat, blockWhenIdle = requestedReadCode == 0).also {
                            it.startSucceeds = requestedStarts
                            it.nextRead = requestedReadCode
                            requestedRecorders.add(it)
                        }
                    }),
                    chosen.downmix,
                    chosen.warmupReads,
                )
            }
        }),
        threadNamePrefix = "test-mic",
        rebuildScheduler = { _, task ->
            timers.put(task)
            AutoCloseable {}
        },
    ).also(sources::add)

    private class Listener : PcmConsumer {
        val frames = AtomicInteger()

        override fun onFrame(frame: PcmFrame) {
            frames.incrementAndGet()
        }
    }

    private fun frame(value: Short = 100) = ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { value }

    private val canonicalCapture = ActiveCapture(
        source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRateHz = 16_000,
        channels = 1,
        downmix = null,
        routedDevice = null,
        aec = AecState.REQUESTED_OFF,
        warmupReads = 0,
    )

    @Test fun aRecorderThatRefusesToStartFallsBackToTheCanonicalRouteInTheSameCapture() {
        requestedStarts = false
        val fan = source(stereo)
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)

        awaitTrue("the canonical recorder starts") { canonicalRecorders.size == 1 && canonicalRecorders[0].recording }
        assertEquals("the stereo recorder was tried first", 1, requestedRecorders.size)
        assertEquals("and released once it refused", 1, requestedRecorders[0].released.get())
        canonicalRecorders[0].offer(frame())
        awaitTrue("the wake word hears the panel") { wake.frames.get() == 1 }
        assertTrue("the source is open, not in error", fan.state.value is MicState.Open)

        val report = fan.routeReport(stereo)
        assertEquals("the report keeps the route that was asked for", stereo, report.requested)
        assertEquals("and shows the route that is really open", canonicalCapture, report.active)
        lease.close()
    }

    @Test fun aRouteWhoseReadsFailBeforeAnyAudioIsGivenUpForTheCanonicalRoute() {
        requestedReadCode = -3
        val fan = source(stereo)
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)

        for (attempt in 1..FallbackCaptureDevices.MAX_ROUTE_FAILURES) {
            awaitTrue("requested generation $attempt fails and asks for a rebuild") { timers.isNotEmpty() && requestedRecorders.size == attempt }
            awaitTrue("and its recorder is released") { requestedRecorders[attempt - 1].released.get() == 1 }
            assertEquals("nothing falls back while the route is still being tried", 0, canonicalRecorders.size)
            fireNextTimer()
        }
        awaitTrue("the next rebuild opens the canonical route") { canonicalRecorders.size == 1 && canonicalRecorders[0].recording }
        assertEquals("without trying the failing route again", FallbackCaptureDevices.MAX_ROUTE_FAILURES, requestedRecorders.size)
        canonicalRecorders[0].offer(frame())
        awaitTrue("the wake word hears the panel") { wake.frames.get() == 1 }
        assertEquals(canonicalCapture, fan.routeReport(stereo).active)
        lease.close()
    }

    @Test fun aRouteThatDeliveredAudioIsRebuiltOnItselfAfterTheAudioServerLosesIt() {
        val fan = source(stereo)
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)
        val pair = ShortArray(MicrophoneSource.SAMPLES_PER_FRAME * 2) { 100 }

        for (generation in 0..FallbackCaptureDevices.MAX_ROUTE_FAILURES) {
            awaitTrue("stereo generation $generation starts") { requestedRecorders.size == generation + 1 && requestedRecorders[generation].recording }
            requestedRecorders[generation].offer(pair)
            awaitTrue("and is heard") { wake.frames.get() == generation + 1 }
            requestedRecorders[generation].offerCode(MIC_READ_DEAD_OBJECT)
            fireNextTimer()
        }
        awaitTrue("the route that worked is tried again") { requestedRecorders.size == FallbackCaptureDevices.MAX_ROUTE_FAILURES + 2 }
        assertEquals("the canonical route is never opened", 0, canonicalRecorders.size)
        lease.close()
    }

    @Test fun theCanonicalRouteIsBuiltDirectly() {
        val fan = source(CaptureRoute())
        val wake = Listener()
        val lease = fan.lease(MicPurpose.WAKE_WORD, consumer = wake, queueFrames = 32)
        awaitTrue("the canonical recorder starts") { canonicalRecorders.size == 1 && canonicalRecorders[0].recording }
        assertEquals(0, requestedRecorders.size)
        lease.close()
    }

    private fun fireNextTimer() {
        val task = timers.poll(5L, TimeUnit.SECONDS)
        assertNotNull("a rebuild was scheduled", task)
        task!!.invoke()
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(1L)
        assertTrue("$what — not satisfied within ${timeoutMs}ms", condition())
    }
}
