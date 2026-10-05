package io.panelassistant.android.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

class BoundedStreamsTest {
    @Test fun acceptsTheExactLimitAndPreservesBytes() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        val output = ByteArrayOutputStream()

        assertEquals(4L, BoundedStreams.copy(ByteArrayInputStream(bytes), output, 4L))
        assertArrayEquals(bytes, output.toByteArray())
        assertArrayEquals(bytes, BoundedStreams.readBytes(ByteArrayInputStream(bytes), 4L))
    }

    @Test fun rejectsOneByteBeyondTheLimitEvenWithoutLengthMetadata() {
        val input = ByteArrayInputStream(byteArrayOf(0, 1, 2, 3, 4))
        try {
            BoundedStreams.copy(input, ByteArrayOutputStream(), 4L)
            fail("over-limit stream must fail")
        } catch (e: ByteLimitExceeded) {
            assertEquals(4L, e.limit)
        }
    }

    @Test fun zeroLimitAcceptsOnlyEmptyInput() {
        assertArrayEquals(byteArrayOf(), BoundedStreams.readBytes(ByteArrayInputStream(byteArrayOf()), 0L))
        try {
            BoundedStreams.readBytes(ByteArrayInputStream(byteArrayOf(1)), 0L)
            fail("non-empty stream must exceed a zero limit")
        } catch (_: ByteLimitExceeded) {
        }
    }

    @Test fun copyExactCopiesTheDeclaredLengthAndStopsWithoutConsumingTrailingBytes() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val output = ByteArrayOutputStream()

        assertEquals(4L, BoundedStreams.copyExact(input, output, 4L))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        assertEquals(5, input.read())
    }

    @Test fun copyExactWritesAvailableBytesThenFailsWhenTheStreamEndsEarly() {
        val output = ByteArrayOutputStream()
        try {
            BoundedStreams.copyExact(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 4L)
            fail("short stream must fail")
        } catch (_: EOFException) {
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
    }

    @Test fun copyExactRetriesAZeroByteReadRatherThanTreatingItAsEndOfStream() {
        val input = object : InputStream() {
            private val bytes = byteArrayOf(7, 8)
            private var index = 0
            private var yieldedZero = false
            override fun read(): Int = throw UnsupportedOperationException("bulk read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!yieldedZero) {
                    yieldedZero = true
                    return 0
                }
                if (index >= bytes.size) return -1
                buffer[offset] = bytes[index++]
                return 1
            }
        }
        val output = ByteArrayOutputStream()

        assertEquals(2L, BoundedStreams.copyExact(input, output, 2L))
        assertArrayEquals(byteArrayOf(7, 8), output.toByteArray())
    }

    @Test fun copyExactZeroLengthWritesNothingAndReadsNothing() {
        val input = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException("no read expected")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw UnsupportedOperationException("no read expected")
        }
        val output = ByteArrayOutputStream()

        assertEquals(0L, BoundedStreams.copyExact(input, output, 0L))
        assertArrayEquals(byteArrayOf(), output.toByteArray())
    }

    @Test fun aggregateBudgetRejectsAComponentThatWouldCrossTheLimit() {
        val budget = BoundedStreams.Budget(4L)

        assertArrayEquals(byteArrayOf(1, 2, 3), budget.accept(byteArrayOf(1, 2, 3)))
        assertEquals(1L, budget.remaining)
        try {
            budget.accept(byteArrayOf(4, 5))
            fail("aggregate payload must not cross its byte budget")
        } catch (e: ByteLimitExceeded) {
            assertEquals(4L, e.limit)
            assertEquals(1L, budget.remaining)
        }
        assertArrayEquals(byteArrayOf(4), budget.accept(byteArrayOf(4)))
        assertEquals(0L, budget.remaining)
    }
}
