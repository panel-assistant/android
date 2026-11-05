package io.panelassistant.android.control

import io.panelassistant.android.CommandQueue
import io.panelassistant.android.hardware.TransferCurve
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.SuccessStickyProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrightnessHardwareWriterTest {
    @Test fun helperWriteClampsInputToTheNodeMaximum() {
        val daemon = FakeDaemon(mapOf("BLREAD" to "0 80", "BLSET 80" to "OK"))

        val route = BrightnessHardwareWriter(daemon).write(999)

        assertEquals(BrightnessWriteRoute.HELPER, route)
        assertEquals(listOf("BLREAD", "BLSET 80"), daemon.sent)
    }

    @Test fun defaultWriterKeepsTheHistoricLinearNodeValue() {
        for (level in intArrayOf(0, 1, 10, 64, 128, 200, 255)) {
            val expected = level * 1023 / 255
            val daemon = FakeDaemon(mapOf("BLREAD" to "0 1023", "BLSET $expected" to "OK"))
            assertEquals(BrightnessWriteRoute.HELPER, BrightnessHardwareWriter(daemon).write(level))
        }
    }

    @Test fun profileCurveShapesTheNodeValue() {
        val curve = TransferCurve.Gamma(2.2, floor = 12 / 255.0)
        val daemon = FakeDaemon(mapOf("BLREAD" to "0 255", "BLSET 65" to "OK", "BLSET 12" to "OK", "BLSET 0" to "OK"))
        val writer = BrightnessHardwareWriter(daemon, curve)
        assertEquals(BrightnessWriteRoute.HELPER, writer.write(128))   // 12 + 243 · 0.502^2.2 = 65.4
        assertEquals(BrightnessWriteRoute.HELPER, writer.write(1))
        assertEquals(BrightnessWriteRoute.HELPER, writer.write(0))
        assertEquals(listOf("BLREAD", "BLSET 65", "BLREAD", "BLSET 12", "BLREAD", "BLSET 0"), daemon.sent)
    }

    @Test fun anUnreachableHelperLeavesNoHardwareRoute() {
        assertEquals(BrightnessWriteRoute.NONE, BrightnessHardwareWriter(FakeDaemon()).write(128))
    }

    @Test fun malformedReadOrFailedSetHasNoSuccessfulRoute() {
        listOf(
            FakeDaemon(mapOf("BLREAD" to "ERR")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "10 0")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "bad 100")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "101 100")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "10 100 extra")) to listOf("BLREAD"),
            FakeDaemon(mapOf("BLREAD" to "10 100", "BLSET 50" to "ERR")) to listOf("BLREAD", "BLSET 50"),
        ).forEach { (daemon, expectedCalls) ->
            assertEquals(BrightnessWriteRoute.NONE, BrightnessHardwareWriter(daemon).write(128))
            assertEquals(expectedCalls, daemon.sent)
        }
    }

    @Test fun helperReadParserAcceptsOnlyCoherentTwoFieldReplies() {
        assertEquals(BacklightReading(10, 100), parseBacklightReading(" 10   100\n"))
        assertEquals(null, parseBacklightReading(null))
        assertEquals(null, parseBacklightReading("-1 100"))
        assertEquals(null, parseBacklightReading("100 99"))
        assertEquals(null, parseBacklightReading("10 100 trailing"))
    }

    @Test fun completeBrightnessTransactionsAreSerialized() {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val applied = CopyOnWriteArrayList<Int>()
        val successfulWrites = BacklightWriteTracker()
        val sequencer = BrightnessWriteSequencer(
            actuator = BrightnessWriteSequencer.Actuator { level ->
                applied += level
                if (level == 10) {
                    firstEntered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                true
            },
            successfulWrites = successfulWrites,
            elapsedRealtimeMs = BrightnessWriteSequencer.ElapsedRealtimeSource { 42L },
        )
        val first = Thread { sequencer.write(10) }.apply { start() }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        val second = Thread { sequencer.write(200) }.apply { start() }
        Thread.sleep(30)
        assertEquals(listOf(10), applied)
        release.countDown()
        first.join(2_000)
        second.join(2_000)
        assertEquals(listOf(10, 200), applied)
        assertEquals(42L, successfulWrites.snapshot())
    }

    @Test fun onlySuccessfulCompleteTransactionsUpdateWriteRecency() {
        val failedWrites = BacklightWriteTracker()
        BrightnessWriteSequencer(
            BrightnessWriteSequencer.Actuator { false },
            failedWrites,
            BrightnessWriteSequencer.ElapsedRealtimeSource { 41L },
        ).write(100)
        assertEquals(Long.MIN_VALUE, failedWrites.snapshot())

        val successfulWrites = BacklightWriteTracker()
        BrightnessWriteSequencer(
            BrightnessWriteSequencer.Actuator { true },
            successfulWrites,
            BrightnessWriteSequencer.ElapsedRealtimeSource { 42L },
        ).write(100)
        assertEquals(42L, successfulWrites.snapshot())
    }

    @Test fun opposedAutoWriteCannotStrandControlsQueuedBehindScreenBrightness() {
        // Former cycle: dispatcher held sequencer -> waited for auto, while auto held its monitor ->
        // waited for sequencer. LED/button commands behind the screen command then never ran.
        val autoBrightnessMonitor = Object()
        val screenActuatorEntered = CountDownLatch(1)
        val releaseScreenActuator = CountDownLatch(1)
        val autoMonitorHeld = CountDownLatch(1)
        val controlsApplied = CountDownLatch(2)
        val observedControls = Collections.synchronizedList(mutableListOf<String>())
        val successfulWrites = BacklightWriteTracker()
        val sequencer = BrightnessWriteSequencer(
            actuator = BrightnessWriteSequencer.Actuator { level ->
                if (level == 10) {
                    screenActuatorEntered.countDown()
                    releaseScreenActuator.await()
                }
                true
            },
            successfulWrites = successfulWrites,
            elapsedRealtimeMs = BrightnessWriteSequencer.ElapsedRealtimeSource { 42L },
        )
        val dispatcher = CommandQueue(threadName = "brightness-lock-order-test")
        val automaticWrite = Thread({
            synchronized(autoBrightnessMonitor) {
                autoMonitorHeld.countDown()
                sequencer.write(200)
            }
        }, "automatic-brightness-lock-order-test").apply { isDaemon = true }

        try {
            assertEquals(CommandQueue.Admission.ACCEPTED, dispatcher.submitLatest("screen") {
                sequencer.write(10)
            })
            assertTrue(screenActuatorEntered.await(1, TimeUnit.SECONDS))
            assertEquals(CommandQueue.Admission.ACCEPTED, dispatcher.submitLatest("led") {
                observedControls += "led"
                controlsApplied.countDown()
            })
            assertEquals(CommandQueue.Admission.ACCEPTED, dispatcher.submitLatest("buttons") {
                observedControls += "buttons"
                controlsApplied.countDown()
            })
            assertEquals(2, dispatcher.pendingCount())
            automaticWrite.start()
            assertTrue(autoMonitorHeld.await(1, TimeUnit.SECONDS))
            val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (automaticWrite.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, automaticWrite.state)
            releaseScreenActuator.countDown()

            assertTrue(
                "opposed automatic/screen writes must not wedge later controls",
                controlsApplied.await(2, TimeUnit.SECONDS),
            )
            automaticWrite.join(2_000)
            assertFalse(automaticWrite.isAlive)
            assertEquals(listOf("led", "buttons"), observedControls)
            assertEquals(42L, successfulWrites.snapshot())
            assertTrue(dispatcher.closeAndDrain(MonotonicDeadline(2_000)).drained)
        } finally {
            releaseScreenActuator.countDown()
            dispatcher.close()
            automaticWrite.interrupt()
            automaticWrite.join(1_000)
        }
    }

    @Test fun anUnavailableProbeRetriesWithBoundedBackoffThenSticksOnSuccess() {
        var now = 10_000L
        var calls = 0
        val probe = SuccessStickyProbe(
            probe = {
                calls++
                if (calls < 3) null else "/sys/class/backlight/panel/"
            },
            nowMs = { now },
            initialBackoffMs = 100L,
            maxBackoffMs = 200L,
        )

        assertEquals(null, probe.get())
        now += 99
        assertEquals(null, probe.get())
        assertEquals(1, calls)
        now += 1
        assertEquals(null, probe.get())
        assertEquals(2, calls)
        now += 199
        assertEquals(null, probe.get())
        assertEquals(2, calls)
        now += 1
        assertEquals("/sys/class/backlight/panel/", probe.get())
        now += 60_000
        assertEquals("/sys/class/backlight/panel/", probe.get())
        assertEquals(3, calls)
    }
}
