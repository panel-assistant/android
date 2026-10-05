package io.panelassistant.android.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the shared microphone core, with the hardware replaced by a device the test clocks.
 *
 * Every wait ends in an assertion rather than an exception, deliberately: a test that dies with a
 * timeout exception proves nothing to a mutation battery, which credits a kill only for an assertion
 * failure in the method it named.
 */
class MicrophoneFanOutTest {

    private val sources = mutableListOf<MicrophoneFanOut>()
    private val gates = mutableListOf<CountDownLatch>()

    @After
    fun releaseEverything() {
        // Free any wedged consumer first, or the shutdowns below would each burn their whole budget.
        gates.forEach { it.countDown() }
        sources.forEach { runCatching { it.shutdown(2_000L) } }
    }

    // ---- (a) a slow consumer costs only its own lease --------------------------------------------

    @Test
    fun slowConsumerLosesOldestFramesWhileSiblingKeepsCadence() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val gate = gate()
        val slow = GatedConsumer(gate)
        val fast = RecordingConsumer()
        // The slow lease is taken first, so it is also enqueued first on every frame: once the fast
        // sibling has frame N, the slow lease has certainly been offered it too.
        val slowLease = fan.lease(MicPurpose.ASSIST, consumer = slow, queueFrames = SLOW_QUEUE)
        val fastLease = fan.lease(MicPurpose.WAKE_WORD, consumer = fast, queueFrames = 4 * FRAMES)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }

        device.offerFrames(FRAMES)

        awaitTrue("the fast lease receives every frame while its sibling is blocked") {
            fast.frames.get() == FRAMES
        }
        assertEquals("capture read every offered frame without waiting on the blocked consumer", FRAMES, device.framesRead)
        assertEquals("the blocked consumer has delivered nothing yet", 0, slow.frames.get())

        gate.countDown()

        awaitTrue("every frame is either delivered to the slow lease or reported as dropped") {
            slow.frames.get() + slow.dropped.get() == FRAMES
        }
        assertTrue("the slow lease is told about its gap", slow.dropped.get() > 0)
        assertTrue(
            "the slow lease keeps no more than its queue depth plus the frame it was holding, was ${slow.frames.get()}",
            slow.frames.get() <= SLOW_QUEUE + 1,
        )
        assertEquals("the fast lease loses nothing", 0, fast.dropped.get())

        slowLease.close()
        fastLease.close()
    }

    // ---- (b) a throwing consumer is isolated -----------------------------------------------------

    @Test
    fun throwingConsumerIsIsolatedFromCaptureAndSiblings() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val thrower = ThrowingConsumer()
        val healthy = RecordingConsumer()
        val throwingLease = fan.lease(MicPurpose.ASSIST, consumer = thrower, queueFrames = 4 * FRAMES)
        val healthyLease = fan.lease(MicPurpose.WAKE_WORD, consumer = healthy, queueFrames = 4 * FRAMES)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }

        device.offerFrames(FRAMES)

        awaitTrue("the healthy sibling receives every frame") { healthy.frames.get() == FRAMES }
        awaitTrue("the throwing consumer keeps being offered frames") { thrower.attempts.get() == FRAMES }
        assertEquals("capture is unaffected by a consumer that throws", FRAMES, device.framesRead)
        val state = fan.state.value
        assertTrue("capture stays open through the failures, was $state", state is MicState.Open)

        throwingLease.close()
        healthyLease.close()
    }

    // ---- (c) the device follows the leases -------------------------------------------------------

    @Test
    fun firstLeaseOpensTheDeviceAndTheLastReleaseClosesIt() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        assertEquals("an unleased source is closed", MicState.Closed, fan.state.value)

        val first = fan.lease(MicPurpose.WAKE_WORD, consumer = RecordingConsumer(), queueFrames = 8)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }

        val second = fan.lease(MicPurpose.ASSIST, consumer = RecordingConsumer(), queueFrames = 8)
        assertEquals("a second lease shares the already-open device", 1, device.openCount.get())
        assertEquals("both holders are listed", 2, holders(fan).size)

        first.close()
        assertEquals("the device stays open while a lease is still held", 0, device.closeCount.get())
        assertEquals("one holder remains", 1, holders(fan).size)

        second.close()
        assertEquals("releasing the last lease closes the device", 1, device.closeCount.get())
        assertEquals("no holders remain", MicState.Closed, fan.state.value)
    }

    @Test
    fun aLeaseTakenWhileCaptureIsExitingGetsItsOwnCapture() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val first = fan.lease(MicPurpose.ASSIST, consumer = RecordingConsumer(), queueFrames = 8)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }

        // The device already calls back into the test between "capture has stopped" and "capture has
        // finished", which is the whole width of the race. Taking the lease from there reproduces it
        // exactly, on one thread, with no timing to get lucky about.
        val late = RecordingConsumer()
        val second = AtomicReference<MicLease?>()
        device.onClose = {
            device.onClose = null
            second.set(fan.lease(MicPurpose.WAKE_WORD, consumer = late, queueFrames = 8))
        }

        first.close()

        awaitTrue("the lease taken during the exit gets a capture of its own") { device.openCount.get() == 2 }
        device.offerFrames(2)
        awaitTrue("and receives frames") { late.frames.get() == 2 }
        assertTrue("the late holder is listed as capturing", holders(fan).single().active)

        second.get()?.close()
    }

    // ---- (d) pause and resume --------------------------------------------------------------------

    @Test
    fun pauseStopsDeliveryAndResumeRestartsWithoutReopeningTheDevice() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val consumer = RecordingConsumer()
        val lease = fan.lease(MicPurpose.ASSIST, consumer = consumer, queueFrames = 64)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }

        device.offerFrames(5)
        awaitTrue("frames arrive while the lease is active") { consumer.frames.get() == 5 }

        lease.pause()
        device.offerFrames(5, from = 5)
        awaitTrue("capture keeps reading while the lease is paused") { device.framesRead == 10 }
        assertEquals("a paused lease is delivered nothing", 5, consumer.frames.get())
        assertEquals("a pause is not a dropped-frame gap", 0, consumer.dropped.get())
        assertFalse("a paused holder is not active", holders(fan).single().active)

        lease.resume()
        device.offerFrames(5, from = 10)
        awaitTrue("delivery restarts on resume") { consumer.frames.get() == 10 }
        assertEquals("resume does not reopen the device", 1, device.openCount.get())
        assertEquals("resume does not release the device", 0, device.closeCount.get())
        assertTrue("a resumed holder is active again", holders(fan).single().active)

        lease.close()
    }

    // ---- (e) holder ordering ---------------------------------------------------------------------

    @Test
    fun holdersAreOrderedByPriorityThenAcquisitionOrder() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val wake = fan.lease(MicPurpose.WAKE_WORD, consumer = RecordingConsumer(), queueFrames = 8)
        val intercom = fan.lease(MicPurpose.INTERCOM, consumer = RecordingConsumer(), queueFrames = 8)
        // Equal priorities on purpose: the tie is broken by which lease was taken first.
        val calibration = fan.lease(MicPurpose.CALIBRATION, priority = 50, consumer = RecordingConsumer(), queueFrames = 8)
        val assist = fan.lease(MicPurpose.ASSIST, priority = 50, consumer = RecordingConsumer(), queueFrames = 8)

        assertEquals(
            "holders are highest priority first, ties in acquisition order",
            listOf(
                MicPurpose.INTERCOM to 70,
                MicPurpose.CALIBRATION to 50,
                MicPurpose.ASSIST to 50,
                MicPurpose.WAKE_WORD to 10,
            ),
            holders(fan).map { it.purpose to it.priority },
        )

        wake.close()
        intercom.close()
        calibration.close()
        assist.close()
    }

    // ---- (f) a failed open, and recovery ---------------------------------------------------------

    @Test
    fun failedOpenLatchesErrorWithHoldersAndRetriesOnlyAfterTheBackoff() {
        val device = FakeCaptureDevice()
        val clock = AtomicLong(1_000L)
        val fan = fanOut(device, clock)
        device.openResult = false

        val lease = fan.lease(MicPurpose.ASSIST, consumer = RecordingConsumer(), queueFrames = 8)
        awaitTrue("a failed open latches an error") { fan.state.value is MicState.Error }

        val failed = fan.state.value as? MicState.Error
        assertNotNull("the failure is reported as Error, was ${fan.state.value}", failed)
        assertEquals("the holder survives a failed open", listOf(MicPurpose.ASSIST), failed!!.holders.map { it.purpose })
        assertFalse("a holder of a device that will not open is not active", failed.holders.single().active)
        assertTrue("the reason names the failure, was '${failed.reason}'", failed.reason.contains("could not be opened"))

        device.openResult = true
        lease.pause()
        lease.resume()
        assertTrue(
            "a resume inside the backoff window does not retry the device, state was ${fan.state.value}",
            fan.state.value is MicState.Error,
        )

        clock.addAndGet(MIC_OPEN_BACKOFF_BASE_MS)
        lease.pause()
        lease.resume()
        // Wait on the device, not on the state: clearing the error publishes Open the instant the
        // retry is admitted, which is before the capture thread it started has opened anything.
        awaitTrue("a resume after the backoff window reopens the device") { device.openCount.get() == 2 }
        awaitTrue("and the holder is capturing again") { holders(fan).single().active }
        assertEquals("recovery opened the device exactly once more", 2, device.openCount.get())
        assertTrue("the source reports itself open, state was ${fan.state.value}", fan.state.value is MicState.Open)

        lease.close()
    }

    @Test
    fun midCaptureReadFailureLatchesErrorAndReleasesTheDevice() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val consumer = RecordingConsumer()
        val lease = fan.lease(MicPurpose.ASSIST, consumer = consumer, queueFrames = 32)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }
        device.offerFrames(1)
        awaitTrue("capture is running") { consumer.frames.get() == 1 }

        device.failNextRead = true
        device.offerFrames(1, from = 1) // unblock a read that is already parked, so the next one fails

        awaitTrue("a failed read latches an error") { fan.state.value is MicState.Error }
        awaitTrue("a failed capture releases the device") { device.closeCount.get() == 1 }
        val state = fan.state.value
        assertTrue("the holder survives a failed read, was $state", state is MicState.Error && state.holders.size == 1)

        lease.close()
    }

    // ---- (g) teardown ----------------------------------------------------------------------------

    @Test
    fun shutdownJoinsEveryThreadAndReportsCompletion() {
        val device = FakeCaptureDevice()
        // A release that takes real time is what makes "shutdown waited" distinguishable from luck.
        device.closeDelayMs = 150L
        val fan = fanOut(device)
        val consumer = RecordingConsumer()
        fan.lease(MicPurpose.ASSIST, consumer = consumer, queueFrames = 32)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }
        device.offerFrames(3)
        awaitTrue("frames are flowing before teardown") { consumer.frames.get() == 3 }

        assertTrue("teardown of healthy consumers completes", fan.shutdown(4_000L))
        assertEquals("shutdown waited for the capture thread to release the device", 1, device.closeCount.get())
        assertEquals("no holders survive shutdown", MicState.Closed, fan.state.value)
    }

    @Test
    fun shutdownReportsIncompleteTeardownWhenAConsumerIsWedged() {
        val device = FakeCaptureDevice()
        val fan = fanOut(device)
        val gate = gate()
        val wedged = GatedConsumer(gate)
        fan.lease(MicPurpose.ASSIST, consumer = wedged, queueFrames = 8)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }
        device.offerFrames(1)
        awaitTrue("the consumer is inside onFrame") { wedged.attempts.get() == 1 }

        assertFalse("a wedged consumer's delivery thread cannot be joined", fan.shutdown(200L))

        gate.countDown()
    }

    @Test
    fun shutdownWaitsForAnAdmittedCaptureAndRefusesItTheHardware() {
        val device = FakeCaptureDevice()
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = gate()
        // The factory parks the capture thread between being admitted and running its body, which is
        // the window shutdown used to be able to step through: a capture that had been recorded but
        // had not yet reached the hardware.
        val fan = MicrophoneFanOut(
            deviceFactory = { device },
            threadNamePrefix = "test-mic",
            threadFactory = { body, name ->
                if (name == "test-mic") {
                    Thread({
                        reached.countDown()
                        release.await(10L, TimeUnit.SECONDS)
                        body.run()
                    }, name)
                } else {
                    Thread(body, name)
                }
            },
        )
        sources.add(fan)

        fan.lease(MicPurpose.ASSIST, consumer = RecordingConsumer(), queueFrames = 8)
        assertTrue("the capture is admitted and parked short of the hardware", reached.await(5L, TimeUnit.SECONDS))
        assertEquals("nothing has been opened yet", 0, device.openCount.get())

        val teardown = AtomicReference<Boolean?>(null)
        val entered = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        Thread({
            entered.countDown()
            teardown.set(fan.shutdown(5_000L))
            done.countDown()
        }, "test-shutdown").start()
        assertTrue("the teardown thread is running", entered.await(5L, TimeUnit.SECONDS))

        assertFalse(
            "shutdown must not conclude while a capture it admitted is still on its way to the microphone",
            done.await(300L, TimeUnit.MILLISECONDS),
        )

        release.countDown()

        assertTrue("shutdown finishes once the admitted capture has gone", done.await(10L, TimeUnit.SECONDS))
        assertEquals("teardown is reported complete", true, teardown.get())
        assertEquals("a capture admitted before shutdown never opens the microphone", 0, device.openCount.get())
        // The refused generation still closes its own device, so that device is revoked for good and
        // cannot be opened by anything that reaches it later.
        assertEquals("and its device is closed rather than left able to open one", 1, device.closeCount.get())
        assertEquals("no holders survive shutdown", MicState.Closed, fan.state.value)
    }

    @Test
    fun eachCaptureGenerationGetsItsOwnDevice() {
        val built = CopyOnWriteArrayList<FakeCaptureDevice>()
        val fan = fanOutOf({ FakeCaptureDevice().also { built.add(it) } })

        val first = fan.lease(MicPurpose.ASSIST, consumer = RecordingConsumer(), queueFrames = 8)
        awaitTrue("the first generation opens its own device") { built.size == 1 && built[0].openCount.get() == 1 }
        first.close()
        awaitTrue("and closes it on the way out") { built[0].closeCount.get() == 1 }

        val second = fan.lease(MicPurpose.WAKE_WORD, consumer = RecordingConsumer(), queueFrames = 8)
        awaitTrue("the next generation builds a fresh one") { built.size == 2 && built[1].openCount.get() == 1 }
        assertEquals("a closed device is never reopened", 1, built[0].openCount.get())

        second.close()
        awaitTrue("which is closed in its turn") { built[1].closeCount.get() == 1 }
    }

    // ---- frame assembly --------------------------------------------------------------------------

    @Test
    fun shortDeviceReadsAreAccumulatedIntoWholeFrames() {
        val device = FakeCaptureDevice(maxChunkSamples = 60)
        val fan = fanOut(device)
        val consumer = RecordingConsumer()
        val lease = fan.lease(MicPurpose.ASSIST, consumer = consumer, queueFrames = 8)
        awaitTrue("the first lease opens the device") { device.openCount.get() == 1 }

        val expected = ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { it.toShort() }
        device.offerFrame(expected.copyOf())

        awaitTrue("three short reads assemble into one frame") { consumer.frames.get() == 1 }
        val received = consumer.lastFrame.get()
        assertNotNull("a frame was delivered", received)
        assertEquals("the frame is exactly one 10 ms period", MicrophoneSource.SAMPLES_PER_FRAME, received!!.size)
        assertArrayEquals("short reads are written at the right offsets, in order", expected, received)

        lease.close()
    }

    // ---- retry policy ----------------------------------------------------------------------------

    @Test
    fun openBackoffDoublesToACeiling() {
        assertEquals("no failures means no wait", 0L, micOpenBackoffMs(0))
        assertEquals("a negative count means no wait", 0L, micOpenBackoffMs(-1))
        assertEquals(200L, micOpenBackoffMs(1))
        assertEquals(400L, micOpenBackoffMs(2))
        assertEquals(800L, micOpenBackoffMs(3))
        assertEquals(1_600L, micOpenBackoffMs(4))
        assertEquals(3_200L, micOpenBackoffMs(5))
        assertEquals("the sixth doubling is capped", 5_000L, micOpenBackoffMs(6))
        assertEquals("the cap holds however long the fault lasts", 5_000L, micOpenBackoffMs(50))
    }

    @Test
    fun openRetryIsAdmittedOnlyOnceTheWindowElapses() {
        assertFalse("a retry before the window is refused", micOpenRetryAllowed(999L, 1_000L))
        assertTrue("a retry exactly on the window is admitted", micOpenRetryAllowed(1_000L, 1_000L))
        assertTrue("a retry after the window is admitted", micOpenRetryAllowed(1_001L, 1_000L))
    }

    // ---- harness ---------------------------------------------------------------------------------

    private fun fanOut(device: PcmCaptureDevice, clock: AtomicLong = AtomicLong(0L)): MicrophoneFanOut =
        fanOutOf({ device }, clock)

    private fun fanOutOf(
        deviceFactory: () -> PcmCaptureDevice,
        clock: AtomicLong = AtomicLong(0L),
    ): MicrophoneFanOut {
        val fan = MicrophoneFanOut(
            deviceFactory = deviceFactory,
            backoffClockMs = { clock.get() },
            nanoTime = { clock.get() * 1_000_000L },
            threadNamePrefix = "test-mic",
        )
        sources.add(fan)
        return fan
    }

    private fun gate(): CountDownLatch = CountDownLatch(1).also { gates.add(it) }

    private fun holders(fan: MicrophoneFanOut): List<MicHolder> = when (val snapshot = fan.state.value) {
        is MicState.Open -> snapshot.holders
        is MicState.Error -> snapshot.holders
        MicState.Closed -> emptyList()
    }

    private fun awaitTrue(what: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(1L)
        assertTrue("$what — not satisfied within ${timeoutMs}ms", condition())
    }

    private companion object {
        const val FRAMES = 50
        const val SLOW_QUEUE = 4
    }
}

private val STOP_READING = Any()

/**
 * A capture device the test feeds by hand. `read` blocks until the test offers audio, so the test
 * owns capture cadence exactly and nothing needs a sleep to be deterministic.
 */
private class FakeCaptureDevice(
    /** Cap on one read, so a test can force the core to assemble a frame from several short reads. */
    private val maxChunkSamples: Int = MicrophoneSource.SAMPLES_PER_FRAME,
) : PcmCaptureDevice {

    @Volatile var openResult = true
    @Volatile var failNextRead = false
    @Volatile var closeDelayMs = 0L

    /** Runs inside `close`, i.e. while the capture thread is between stopping and finishing. */
    @Volatile var onClose: (() -> Unit)? = null

    val openCount = AtomicInteger()
    val closeCount = AtomicInteger()
    private val samplesRead = AtomicInteger()

    val framesRead: Int get() = samplesRead.get() / MicrophoneSource.SAMPLES_PER_FRAME

    private val pending = LinkedBlockingQueue<Any>()
    private var current: ShortArray? = null
    private var consumed = 0

    fun offerFrame(samples: ShortArray) {
        pending.put(samples)
    }

    /** Offer [count] distinguishable frames; sample `i` of frame `n` is `n * 1000 + i`. */
    fun offerFrames(count: Int, from: Int = 0) {
        repeat(count) { n ->
            offerFrame(ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { i -> ((from + n) * 1_000 + i).toShort() })
        }
    }

    override fun open(): Boolean {
        openCount.incrementAndGet()
        pending.clear()
        current = null
        consumed = 0
        return openResult
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int {
        if (failNextRead) {
            failNextRead = false
            return -1
        }
        var chunk = current
        if (chunk == null || consumed >= chunk.size) {
            val next = try {
                pending.take()
            } catch (interrupted: InterruptedException) {
                return 0
            }
            if (next === STOP_READING) return 0
            chunk = next as ShortArray
            current = chunk
            consumed = 0
        }
        val count = minOf(maxSamples, chunk.size - consumed, maxChunkSamples)
        System.arraycopy(chunk, consumed, into, offsetSamples, count)
        consumed += count
        samplesRead.addAndGet(count)
        return count
    }

    override fun stop() {
        pending.put(STOP_READING)
    }

    override fun close() {
        if (closeDelayMs > 0L) Thread.sleep(closeDelayMs)
        closeCount.incrementAndGet()
        onClose?.invoke()
    }
}

private open class RecordingConsumer : PcmConsumer {
    val frames = AtomicInteger()
    val dropped = AtomicInteger()
    val gaps = AtomicInteger()
    val lastFrame = AtomicReference<ShortArray?>()

    override fun onFrame(frame: PcmFrame) {
        lastFrame.set(frame.samples)
        frames.incrementAndGet()
    }

    override fun onDropped(count: Int) {
        dropped.addAndGet(count)
        gaps.incrementAndGet()
    }
}

/** Holds its delivery thread inside `onFrame` until the test opens the gate. */
private class GatedConsumer(private val gate: CountDownLatch) : RecordingConsumer() {
    val attempts = AtomicInteger()

    override fun onFrame(frame: PcmFrame) {
        attempts.incrementAndGet()
        gate.await(10L, TimeUnit.SECONDS)
        super.onFrame(frame)
    }
}

private class ThrowingConsumer : RecordingConsumer() {
    val attempts = AtomicInteger()

    override fun onFrame(frame: PcmFrame) {
        attempts.incrementAndGet()
        throw IllegalStateException("this consumer always fails")
    }
}
