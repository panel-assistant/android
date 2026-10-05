package io.panelassistant.android.shizuku

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuCommandRunnerTest {
    @Test fun completedBoundedOutputIsReturnedWithoutTerminatingTheProcess() {
        val output = TrackingInputStream(ByteArrayInputStream("reply".toByteArray()))
        val process = FakeProcess(output, true)

        assertEquals(0 to "reply", ShizukuCommandRunner(maxReplyBytes = 5).run(process, 100))
        assertTrue(output.closed)
        assertFalse(process.destroyed)
        assertEquals(1, process.waitCalls)
    }

    @Test fun oversizedOutputFailsClosedAndCleansUpTheProcess() {
        val output = TrackingInputStream(ByteArrayInputStream("oversized".toByteArray()))
        val process = FakeProcess(output, true, true)

        assertNull(ShizukuCommandRunner(maxReplyBytes = 4).run(process, 100))
        assertTrue(output.closed)
        assertTrue(process.destroyed)
        assertEquals(2, process.waitCalls)
    }

    @Test fun timeoutClosesTheReaderAndWaitsForForcedTermination() {
        val output = CloseUnblocksInputStream()
        val process = FakeProcess(output, false, true)

        assertNull(ShizukuCommandRunner(maxReplyBytes = 4).run(process, 100))
        assertTrue(output.closed)
        assertEquals(0L, output.readerCompleted.count)
        assertTrue(process.destroyed)
        assertEquals(2, process.waitCalls)
    }

    @Test fun completedChildWithUnfinishedReaderFailsClosedAndCleansUp() {
        val output = CloseUnblocksInputStream()
        val process = FakeProcess(output, true, true).apply {
            beforeFirstWait = {
                assertTrue(output.readerStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
            }
        }

        assertNull(
            ShizukuCommandRunner(
                maxReplyBytes = 4,
                readerJoinTimeoutMs = 10,
                destroyWaitTimeoutMs = 10,
            ).run(process, 100),
        )
        assertTrue(output.closed)
        assertEquals(0L, output.readerCompleted.count)
        assertTrue(process.destroyed)
        assertEquals(2, process.waitCalls)
    }

    private class FakeProcess(
        override val output: InputStream,
        vararg waitResults: Boolean,
    ) : ShizukuCommandProcess {
        private val waits = ArrayDeque(waitResults.toList())
        var waitCalls = 0
        var destroyed = false
        var beforeFirstWait: (() -> Unit)? = null

        override fun waitFor(timeoutMs: Long): Boolean {
            if (waitCalls == 0) beforeFirstWait?.invoke()
            waitCalls++
            return waits.removeFirst()
        }

        override fun exitValue(): Int = 0

        override fun closeStreams() {
            output.close()
        }

        override fun destroyForcibly() {
            destroyed = true
        }
    }

    private class TrackingInputStream(delegate: InputStream) : java.io.FilterInputStream(delegate) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class CloseUnblocksInputStream : InputStream() {
        private val closedLatch = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val readerCompleted = CountDownLatch(1)
        var closed = false

        override fun read(): Int {
            readerStarted.countDown()
            closedLatch.await()
            readerCompleted.countDown()
            return -1
        }

        override fun close() {
            closed = true
            closedLatch.countDown()
        }
    }
}
