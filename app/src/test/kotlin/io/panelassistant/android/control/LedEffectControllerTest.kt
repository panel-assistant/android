package io.panelassistant.android.control

import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.LedEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle invariants for [LedEffectController], driven on a REAL background dispatcher (the cancel-and-
 * join in start/stop runs under runBlocking, so a virtual-time TestScope would deadlock). The frame math
 * itself is covered by LedEffectsTest; here we prove the loop is a single, cleanly-stoppable owner:
 * stop() leaves no stale frame, and start() replaces (never orphans) a running loop.
 */
class LedEffectControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    /** Records every HAL write so the test can reason about ordering + counts across threads. */
    private class RecordingLed : LedController {
        val writes = CopyOnWriteArrayList<String>()
        @Volatile var failAt = Int.MAX_VALUE
        private var attempts = 0
        override fun available() = true
        override fun colorCapable() = true
        @Synchronized override fun setRgb(r: Int, g: Int, b: Int): Boolean {
            writes.add("rgb:$r,$g,$b")
            return ++attempts != failAt
        }
        @Synchronized override fun off(): Boolean {
            writes.add("off")
            return ++attempts != failAt
        }
    }

    private fun awaitUntil(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test fun startDrivesFramesToTheLed() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)
        fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255)
        awaitUntil { led.writes.any { it == "rgb:200,0,0" } } // a lit red frame reached the HAL
        assertTrue("effect is running", fx.running())
        fx.stop()
    }

    @Test fun stopHaltsWithNoFrameAfterItReturns() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)
        fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255)
        awaitUntil { led.writes.size >= 2 }         // let a couple of frames run

        fx.stop()                                    // cancel-and-join: no frame may fire after this returns
        val countAtReturn = led.writes.size
        assertFalse("not running after stop", fx.running())

        // If a stale in-flight frame could survive the join, it would land within a few stepMs (80ms).
        Thread.sleep(300)
        assertEquals("no LED write after stop() returned", countAtReturn, led.writes.size)
    }

    @Test fun startReplacesTheRunningEffectWithoutOrphaningIt() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)

        fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255)   // red
        awaitUntil { led.writes.any { it == "rgb:200,0,0" } }

        fx.start(LedEffects.Effect.STROBE, r = 0, g = 200, b = 0, br = 255)   // green replaces red
        awaitUntil { led.writes.any { it == "rgb:0,200,0" } }
        val firstGreen = led.writes.indexOfFirst { it == "rgb:0,200,0" }

        fx.stop()

        // Once the green loop is live, the red (old) loop must be gone — no red frame may appear after it.
        val redAfterGreen = led.writes.withIndex().any { (i, w) -> i > firstGreen && w == "rgb:200,0,0" }
        assertFalse("old effect loop was orphaned (red frame after the green switch)", redAfterGreen)
    }

    @Test fun startFailsBeforePublishingOwnershipWhenTheFirstFrameIsRejected() {
        val led = RecordingLed().apply { failAt = 1 }
        val fx = LedEffectController(led, scope)

        assertFalse(fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255))
        assertEquals(LedEffectController.Status.FAILED, fx.status())
        assertFalse(fx.running())
    }

    @Test fun aLaterRejectedFrameTerminatesTheOwnedEffect() {
        val led = RecordingLed().apply { failAt = 2 }
        val fx = LedEffectController(led, scope)

        assertTrue(fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255))
        awaitUntil { fx.status() == LedEffectController.Status.FAILED }

        assertFalse(fx.running())
        val writesAtFailure = led.writes.size
        Thread.sleep(200)
        assertEquals("failed effect must not keep issuing frames", writesAtFailure, led.writes.size)
    }

    @Test fun solidReplacementIsTheLastConfirmedWrite() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)
        assertTrue(fx.start(LedEffects.Effect.STROBE, r = 200, g = 0, b = 0, br = 255))

        assertTrue(fx.setSolid(1, 2, 3))
        val countAtReturn = led.writes.size
        Thread.sleep(200)

        assertEquals("rgb:1,2,3", led.writes.last())
        assertEquals(countAtReturn, led.writes.size)
        assertEquals(LedEffectController.Status.IDLE, fx.status())
    }

    @Test fun closeIsTerminalForEffectsSolidAndOff() {
        val led = RecordingLed()
        val fx = LedEffectController(led, scope)
        assertTrue(fx.setSolid(1, 2, 3))
        fx.close()
        val countAtClose = led.writes.size

        assertFalse(fx.start(LedEffects.Effect.BLINK, 1, 2, 3, 255))
        assertFalse(fx.setSolid(4, 5, 6))
        assertFalse(fx.setOff())
        assertEquals(countAtClose, led.writes.size)
        assertEquals(LedEffectController.Status.CLOSED, fx.status())
    }

    @Test fun replacementWaitsForTheInFlightFrameBeforeItsFinalWrite() {
        val led = BlockingSecondFrameLed()
        val fx = LedEffectController(led, scope)
        assertTrue(fx.start(LedEffects.Effect.STROBE, 200, 0, 0, 255))
        assertTrue(led.blockedFrameEntered.await(1, TimeUnit.SECONDS))
        val replacementReturned = CountDownLatch(1)
        val replacement = Thread {
            assertTrue(fx.setSolid(1, 2, 3))
            replacementReturned.countDown()
        }.apply { start() }

        assertFalse("replacement must wait while the old HAL write is in flight", replacementReturned.await(100, TimeUnit.MILLISECONDS))
        led.releaseBlockedFrame.countDown()
        assertTrue(replacementReturned.await(1, TimeUnit.SECONDS))
        replacement.join(1_000)

        assertEquals("rgb:1,2,3", led.writes.last())
        assertFalse(replacement.isAlive)
    }

    private class BlockingSecondFrameLed : LedController {
        val writes = CopyOnWriteArrayList<String>()
        val blockedFrameEntered = CountDownLatch(1)
        val releaseBlockedFrame = CountDownLatch(1)
        private val attempts = AtomicInteger()
        override fun available() = true
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int): Boolean {
            writes += "rgb:$r,$g,$b"
            blockSecondFrame()
            return true
        }
        override fun off(): Boolean {
            writes += "off"
            blockSecondFrame()
            return true
        }
        private fun blockSecondFrame() {
            if (attempts.incrementAndGet() == 2) {
                blockedFrameEntered.countDown()
                releaseBlockedFrame.await(1, TimeUnit.SECONDS)
            }
        }
    }
}

/**
 * The hold: the camera-in-use indication takes the LED while the screen is off, and nothing else may
 * paint over it until the hold is released. Ordinary writes are refused rather than queued, so the
 * MQTT path persists intent and reports the actuation as unknown instead of pretending it happened.
 */
class LedEffectControllerHoldTest {

    private class RecordingLed : LedController {
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun available() = true
        override fun colorCapable() = true
        override fun setRgb(r: Int, g: Int, b: Int): Boolean { writes.add("rgb:$r,$g,$b"); return true }
        override fun off(): Boolean { writes.add("off"); return true }
    }

    @Test fun ordinaryWritesAreRefusedWhileHeldAndTheHolderWritesThrough() {
        val led = RecordingLed()
        val controller = LedEffectController(led)
        val hold = requireNotNull(controller.hold())
        assertTrue(controller.held())

        assertFalse("a user command must not overwrite the indication", controller.setSolid(0, 0, 255))
        assertFalse(controller.setOff())
        assertFalse(controller.start(LedEffects.Effect.PULSE, 0, 255, 0, 255))
        assertEquals(emptyList<String>(), led.writes)

        assertTrue(hold.setSolid(255, 0, 0))
        assertEquals(listOf("rgb:255,0,0"), led.writes)
    }

    @Test fun aSecondHoldIsRefusedUntilTheFirstIsClosed() {
        val controller = LedEffectController(RecordingLed())
        val first = requireNotNull(controller.hold())
        assertEquals(null, controller.hold())
        first.close()
        assertFalse(controller.held())
        assertTrue(requireNotNull(controller.hold()).active)
    }

    @Test fun releasingRestoresNothingByItselfAndReopensOrdinaryWrites() {
        val led = RecordingLed()
        val controller = LedEffectController(led)
        val hold = requireNotNull(controller.hold())
        assertTrue(hold.setSolid(255, 0, 0))
        hold.close()
        assertFalse(hold.active)
        assertFalse("a closed hold no longer writes", hold.setSolid(1, 2, 3))
        assertEquals(listOf("rgb:255,0,0"), led.writes)
        assertTrue(controller.setSolid(0, 0, 255))
        assertEquals(listOf("rgb:255,0,0", "rgb:0,0,255"), led.writes)
    }

    @Test fun closeDropsTheHoldSoTeardownIsNeverBlockedByAnIndication() {
        val controller = LedEffectController(RecordingLed())
        val hold = requireNotNull(controller.hold())
        controller.close()
        assertFalse(hold.active)
        assertFalse(controller.held())
        assertEquals(null, controller.hold())
    }
}
