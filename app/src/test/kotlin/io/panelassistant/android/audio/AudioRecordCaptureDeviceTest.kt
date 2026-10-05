package io.panelassistant.android.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A capture device may never leave a recorder running that nothing can reach.
 *
 * The microphone goes live the instant a recorder is started, so the dangerous interval is between
 * building one and publishing it. Anything arriving there would once have found nothing published,
 * stopped nothing and returned, after which the open published a recorder that was already
 * recording — a teardown reporting itself complete over a live microphone. These tests hold an open
 * in exactly that interval and revoke the device from another thread.
 */
class AudioRecordCaptureDeviceTest {

    @Test
    fun closingDuringConstructionRefusesTheOpenAndReleasesTheRecorder() {
        val built = CopyOnWriteArrayList<FakeRecorder>()
        val parked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val device = AudioRecordCaptureDevice(
            openRecorder = { FakeRecorder().also { built.add(it) } },
            // Only the first open parks; the second is the already-revoked case asserted below.
            afterConstruction = {
                if (built.size == 1) {
                    parked.countDown()
                    proceed.await(10L, TimeUnit.SECONDS)
                }
            },
        )

        val opened = AtomicReference<Boolean?>(null)
        val finished = CountDownLatch(1)
        Thread({
            opened.set(device.open())
            finished.countDown()
        }, "test-open").start()

        assertTrue("the open is parked with a recorder built", parked.await(5L, TimeUnit.SECONDS))
        assertEquals("exactly one recorder was built", 1, built.size)
        assertEquals("and nothing has started it yet", 0, built[0].started.get())

        device.close()
        proceed.countDown()

        assertTrue("the parked open finishes", finished.await(10L, TimeUnit.SECONDS))
        assertEquals("an open overtaken by a close refuses", false, opened.get())
        assertEquals("the recorder is never started", 0, built[0].started.get())
        assertFalse("so it cannot be left recording", built[0].recording)
        assertEquals("and it is released, not abandoned", 1, built[0].released.get())

        assertEquals("a later open on a revoked device also refuses", false, device.open())
        assertEquals("even though it built a recorder to find that out", 2, built.size)
        assertEquals("that recorder is never started either", 0, built[1].started.get())
        assertEquals("and is released too", 1, built[1].released.get())
    }

    @Test
    fun stoppingDuringConstructionAlsoRefusesTheOpen() {
        val built = CopyOnWriteArrayList<FakeRecorder>()
        val parked = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val device = AudioRecordCaptureDevice(
            openRecorder = { FakeRecorder().also { built.add(it) } },
            afterConstruction = {
                parked.countDown()
                proceed.await(10L, TimeUnit.SECONDS)
            },
        )
        val opened = AtomicReference<Boolean?>(null)
        val finished = CountDownLatch(1)
        Thread({ opened.set(device.open()); finished.countDown() }, "test-open").start()
        assertTrue("the open is parked with a recorder built", parked.await(5L, TimeUnit.SECONDS))

        // stop() exists to unblock a reader, but it revokes for the same reason close() does: a
        // device that has been told to stop must never go on to start something.
        device.stop()
        proceed.countDown()

        assertTrue("the parked open finishes", finished.await(10L, TimeUnit.SECONDS))
        assertEquals("an open overtaken by a stop refuses", false, opened.get())
        assertEquals("the recorder is never started", 0, built[0].started.get())
        assertEquals("and is released", 1, built[0].released.get())
    }

    @Test
    fun anUninterruptedOpenPublishesTheRecorderAndCloseReleasesIt() {
        val recorder = FakeRecorder()
        val device = AudioRecordCaptureDevice(openRecorder = { recorder })

        assertTrue("an open that nothing interrupts succeeds", device.open())
        assertEquals("the recorder is started exactly once", 1, recorder.started.get())
        assertTrue("and is recording", recorder.recording)

        device.close()
        assertFalse("close stops it", recorder.recording)
        assertEquals("and releases it", 1, recorder.released.get())
    }

    @Test
    fun aSecondOpenOnALiveDeviceIsRefusedWithoutDisturbingTheFirst() {
        val built = CopyOnWriteArrayList<FakeRecorder>()
        val device = AudioRecordCaptureDevice(openRecorder = { FakeRecorder().also { built.add(it) } })

        assertTrue(device.open())
        assertEquals("a device already holding a recorder refuses a second open", false, device.open())
        assertEquals("the second recorder is never started", 0, built[1].started.get())
        assertEquals("and is released rather than leaked", 1, built[1].released.get())
        assertTrue("the first recorder is untouched", built[0].recording)
        assertEquals("and never released behind the caller's back", 0, built[0].released.get())
    }

    @Test
    fun aRecorderThatWillNotStartIsStoppedAndReleasedAndTheOpenRefuses() {
        val recorder = FakeRecorder().apply { startSucceeds = false }
        val device = AudioRecordCaptureDevice(openRecorder = { recorder })

        assertEquals("a recorder that refuses to start fails the open", false, device.open())
        assertEquals("it is stopped before release, never left half-started", 1, recorder.stopped.get())
        assertEquals("and released", 1, recorder.released.get())
    }

    @Test
    fun aRecorderThatCannotBeBuiltFailsTheOpenWithoutTouchingAnything() {
        val device = AudioRecordCaptureDevice(openRecorder = { null })
        assertEquals("no recorder means no open", false, device.open())
        assertEquals("and a read has nothing to read", 0, device.read(ShortArray(160), 0, 160))
    }

    @Test
    fun readsReportACleanEndOnceTheDeviceIsRevoked() {
        val recorder = FakeRecorder()
        val device = AudioRecordCaptureDevice(openRecorder = { recorder })
        assertTrue(device.open())
        recorder.nextRead = 160
        assertEquals("a live device reads", 160, device.read(ShortArray(160), 0, 160))

        device.stop()
        assertEquals(
            "a revoked device reports a clean end rather than a fault the source would latch",
            0,
            device.read(ShortArray(160), 0, 160),
        )
    }
}

/** A recorder a test can watch: every transition it makes is counted. */
private class FakeRecorder : PcmRecorder {
    val started = AtomicInteger()
    val stopped = AtomicInteger()
    val released = AtomicInteger()

    @Volatile var recording = false
    @Volatile var startSucceeds = true
    @Volatile var nextRead = 0

    override fun startRecording(): Boolean {
        started.incrementAndGet()
        recording = startSucceeds
        return startSucceeds
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int = nextRead

    override fun stop() {
        stopped.incrementAndGet()
        recording = false
    }

    override fun release() {
        released.incrementAndGet()
    }
}
