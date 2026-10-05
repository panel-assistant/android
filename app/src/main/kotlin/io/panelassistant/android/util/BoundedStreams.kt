package io.panelassistant.android.util

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal class ByteLimitExceeded(val limit: Long) : Exception("payload exceeds $limit bytes")

/** Stream-copy helpers whose limit is enforced even when a sender omits or lies about Content-Length. */
internal object BoundedStreams {
    /** Shared byte budget for a response assembled from several independently read payloads. */
    class Budget(val limit: Long) {
        var remaining: Long = limit
            private set

        init {
            require(limit >= 0L)
        }

        fun accept(bytes: ByteArray): ByteArray {
            if (bytes.size.toLong() > remaining) throw ByteLimitExceeded(limit)
            remaining -= bytes.size
            return bytes
        }
    }

    fun copy(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes in 0 until Long.MAX_VALUE)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val remainingWithProbe = (maxBytes - total) + 1L
            val wanted = minOf(buffer.size.toLong(), remainingWithProbe).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) return total
            if (read == 0) continue
            if (total + read > maxBytes) throw ByteLimitExceeded(maxBytes)
            output.write(buffer, 0, read)
            total += read
        }
    }

    /**
     * Copy exactly [expectedBytes] from [input] to [output], reading no further than that length so a
     * caller can inspect any trailing bytes. A stream that ends early is an [EOFException]; a zero-byte
     * intermediate read is retried rather than treated as end-of-stream.
     */
    fun copyExact(input: InputStream, output: OutputStream, expectedBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = expectedBytes
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("stream ended before declared length")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
        return expectedBytes
    }

    fun readBytes(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        copy(input, output, maxBytes)
        return output.toByteArray()
    }
}
