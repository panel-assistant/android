package io.panelassistant.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class ShizukuInstallRunnerTest {
    @Test fun copyTimeIsChargedToTheProcessWaitBudget() {
        var now = 0L
        val process = FakeProcess(input = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                super.write(buffer, offset, length)
                now += 30_000_000L
            }
        })
        val runner = ShizukuInstallRunner(
            timeoutMs = 100,
            nanoTime = { now },
            deadlineFactory = { _, _ -> AutoCloseable {} },
        )

        assertEquals("Success", runner.run(ByteArrayInputStream(byteArrayOf(1, 2)), 2, process))
        assertEquals(70L, process.waits.single())
        assertFalse(process.destroyed)
    }

    @Test fun deadlineDuringBlockedSourceReadClosesTransportAndKillsChild() {
        lateinit var fireDeadline: () -> Unit
        val source = object : InputStream() {
            var closed = false
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                fireDeadline()
                throw java.io.IOException("deadline closed source")
            }
            override fun close() { closed = true }
        }
        val process = FakeProcess()
        val runner = ShizukuInstallRunner(
            timeoutMs = 100,
            deadlineFactory = { _, onTimeout ->
                fireDeadline = onTimeout
                AutoCloseable {}
            },
        )

        assertNull(runner.run(source, 1, process))
        assertTrue(source.closed)
        assertTrue(process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.outputClosed)
    }

    @Test fun deadlineWhileWaitingKillsChildBeforeFailureReturns() {
        lateinit var fireDeadline: () -> Unit
        val process = FakeProcess(onFirstWait = {
            fireDeadline()
            false
        })
        val runner = ShizukuInstallRunner(
            timeoutMs = 100,
            deadlineFactory = { _, onTimeout ->
                fireDeadline = onTimeout
                AutoCloseable {}
            },
        )

        assertNull(runner.run(ByteArrayInputStream(byteArrayOf(1)), 1, process))
        assertTrue(process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.outputClosed)
    }

    @Test fun deadlineDuringBlockedWriteKillsChildBeforeInstallCanCommit() {
        lateinit var fireDeadline: () -> Unit
        val blockedInput = object : OutputStream() {
            var closed = false
            override fun write(value: Int) = error("bulk write expected")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                fireDeadline()
                throw java.io.IOException("deadline closed pm stdin")
            }
            override fun close() { closed = true }
        }
        val process = FakeProcess(input = blockedInput)
        val runner = ShizukuInstallRunner(
            timeoutMs = 100,
            deadlineFactory = { _, onTimeout ->
                fireDeadline = onTimeout
                AutoCloseable {}
            },
        )

        assertNull(runner.run(ByteArrayInputStream(byteArrayOf(1)), 1, process))
        assertTrue(blockedInput.closed)
        assertTrue(process.destroyed)
        assertEquals(listOf(1_000L), process.waits)
    }

    @Test fun extraBytesFailClosedWithoutStartingInstallWait() {
        val process = FakeProcess()
        val runner = ShizukuInstallRunner(
            timeoutMs = 100,
            deadlineFactory = { _, _ -> AutoCloseable {} },
        )

        assertNull(runner.run(ByteArrayInputStream(byteArrayOf(1, 2)), 1, process))
        assertTrue(process.destroyed)
        assertEquals(listOf(1_000L), process.waits)
    }

    private class FakeProcess(
        override val input: OutputStream = TrackingOutputStream(),
        outputBytes: ByteArray = "Success\n".toByteArray(),
        private val onFirstWait: (() -> Boolean)? = null,
    ) : ShizukuInstallProcess {
        private val trackingInput = input as? TrackingOutputStream
        private val trackingOutput = TrackingInputStream(outputBytes)
        override val output: InputStream = trackingOutput
        val waits = mutableListOf<Long>()
        var destroyed = false
        val inputClosed get() = trackingInput?.closed ?: true
        val outputClosed get() = trackingOutput.closed

        override fun waitFor(timeoutMs: Long): Boolean {
            waits += timeoutMs
            return if (waits.size == 1 && onFirstWait != null) onFirstWait.invoke() else true
        }

        override fun destroyForcibly() { destroyed = true }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
