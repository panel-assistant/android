package io.panelassistant.android

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioPlaybackPreparationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun monoAndStereoPreserveEverySampleAndAppendSixHundredMillisecondsOfSilence() {
        for (channels in 1..2) {
            for (rate in listOf(22050, 44100, 48000)) {
                val file = temporary.newFile()
                val pcm = ByteArray(100 * channels * 2) { (it * 31 + 1).toByte() }
                PcmTailWaveWriter(file, rate, channels).use { writer ->
                    writer.append(ByteBuffer.wrap(pcm, 0, 40 * channels * 2))
                    writer.append(ByteBuffer.wrap(pcm, 40 * channels * 2, 60 * channels * 2))
                    writer.finish()
                }
                val bytes = file.readBytes()
                val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val tailSize = rate * 600 / 1000 * channels * 2
                assertEquals(44 + pcm.size + tailSize, bytes.size)
                assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                assertEquals(bytes.size - 8, header.getInt(4))
                assertEquals("WAVEfmt ", String(bytes, 8, 8, Charsets.US_ASCII))
                assertEquals(16, header.getInt(16))
                assertEquals(1, header.getShort(20).toInt())
                assertEquals(channels, header.getShort(22).toInt())
                assertEquals(rate, header.getInt(24))
                assertEquals(rate * channels * 2, header.getInt(28))
                assertEquals(channels * 2, header.getShort(32).toInt())
                assertEquals(16, header.getShort(34).toInt())
                assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
                assertEquals(pcm.size + tailSize, header.getInt(40))
                assertArrayEquals(pcm, bytes.copyOfRange(44, 44 + pcm.size))
                assertArrayEquals(ByteArray(tailSize), bytes.copyOfRange(44 + pcm.size, bytes.size))
            }
        }
    }

    @Test fun respectsBufferWindowWithoutIncludingUnrelatedBytes() {
        val file = temporary.newFile()
        val buffer = ByteBuffer.wrap(byteArrayOf(9, 9, 1, 2, 3, 4, 8, 8))
        buffer.position(2)
        buffer.limit(6)
        PcmTailWaveWriter(file, 8000, 1, tailMs = 0).use {
            it.append(buffer)
            it.finish()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes().copyOfRange(44, 48))
    }

    @Test fun rejectsTruncatedMonoAndStereoFrames() {
        for ((channels, bytes) in listOf(1 to 3, 2 to 6)) {
            PcmTailWaveWriter(temporary.newFile(), 22050, channels).use {
                assertThrows(IOException::class.java) { it.append(ByteBuffer.allocate(bytes)) }
            }
        }
    }

    @Test fun byteLimitReservesHeaderAndTailBeforeAcceptingSamples() {
        val file = temporary.newFile()
        // 8 kHz mono: 600 ms is 9600 bytes. Only two PCM frames fit this budget.
        PcmTailWaveWriter(file, 8000, 1, maxBytes = 44 + 9600 + 4L).use {
            it.append(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
            assertThrows(IOException::class.java) { it.append(ByteBuffer.allocate(2)) }
            it.finish()
        }
        assertEquals(9648L, file.length())
    }

    @Test fun rejectsEmptyAudioAndInvalidFormats() {
        PcmTailWaveWriter(temporary.newFile(), 22050, 1).use {
            assertThrows(IOException::class.java) { it.finish() }
        }
        assertThrows(IllegalArgumentException::class.java) { PcmTailWaveWriter(temporary.newFile(), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { PcmTailWaveWriter(temporary.newFile(), 22050, 3) }
        assertThrows(IllegalArgumentException::class.java) { PcmTailWaveWriter(temporary.newFile(), 22050, 1, maxBytes = 44) }
    }

    @Test fun completedWaveCannotBeAppendedOrFinalizedTwice() {
        PcmTailWaveWriter(temporary.newFile(), 22050, 1).use {
            it.append(ByteBuffer.allocate(2))
            it.finish()
            assertThrows(IllegalStateException::class.java) { it.append(ByteBuffer.allocate(2)) }
            assertThrows(IllegalStateException::class.java) { it.finish() }
        }
    }
}
