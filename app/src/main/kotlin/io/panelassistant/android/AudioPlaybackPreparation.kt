package io.panelassistant.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal interface AudioPlaybackPreparation {
    suspend fun prepare(source: File, destination: File)
    fun cancel()
}

/** Decodes through EOS and adds actual silent frames so speaker drain cannot eat the last syllable. */
internal class AndroidPcmAudioPreparation : AudioPlaybackPreparation {
    private val cancelled = AtomicBoolean(false)

    override fun cancel() { cancelled.set(true) }

    override suspend fun prepare(source: File, destination: File) = withContext(Dispatchers.IO) {
        require(source.canonicalFile != destination.canonicalFile)
        val context = coroutineContext
        val started = System.nanoTime()
        fun checkActive() {
            context.ensureActive()
            if (cancelled.get()) throw CancellationException("audio preparation cancelled")
            if (System.nanoTime() - started > 30_000_000_000L) throw IOException("audio decode deadline exceeded")
        }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var writer: PcmTailWaveWriter? = null
        try {
            checkActive()
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("media contains no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            fun openWriter(output: MediaFormat): PcmTailWaveWriter {
                val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else AudioFormat.ENCODING_PCM_16BIT
                if (encoding != AudioFormat.ENCODING_PCM_16BIT) throw IOException("unsupported decoded PCM encoding")
                return PcmTailWaveWriter(destination, output.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    output.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            }
            if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                writer = openWriter(format)
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (true) {
                    checkActive()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size == 0) throw IOException("empty PCM sample")
                    buffer.position(0)
                    buffer.limit(size)
                    writer.append(buffer)
                    extractor.advance()
                }
            } else {
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                decoder = codec
                codec.configure(format, null, null, 0)
                codec.start()
                var inputEnded = false
                var outputEnded = false
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    checkActive()
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: throw IOException("missing audio input buffer")
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (writer != null) throw IOException("audio format changed during decode")
                        writer = openWriter(codec.outputFormat)
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = codec.getOutputBuffer(index) ?: throw IOException("missing audio output buffer")
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                (writer ?: throw IOException("missing decoded audio format")).append(output)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            checkActive()
            (writer ?: throw IOException("no decoded audio")).finish()
            checkActive()
        } catch (error: Throwable) {
            runCatching { writer?.close() }
            destination.delete()
            throw error
        } finally {
            runCatching { writer?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}

/** Streaming PCM16 WAV writer; the byte limit includes the header and silent tail. */
internal class PcmTailWaveWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    tailMs: Int = 600,
    private val maxBytes: Long = 64L * 1024 * 1024,
) : AutoCloseable {
    private val output: RandomAccessFile
    private val tailBytes: Long
    private var pcmBytes = 0L
    private var finished = false

    init {
        require(sampleRate in 8_000..192_000 && channels in 1..2) { "unsupported PCM format" }
        require(tailMs in 0..5_000)
        tailBytes = sampleRate.toLong() * tailMs / 1000 * channels * 2
        require(maxBytes in (44 + tailBytes)..0xffff_ffffL) { "invalid WAV byte limit" }
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(0)
            output.write(ByteArray(44))
        } catch (error: Throwable) {
            runCatching { output.close() }
            throw error
        }
    }

    fun append(buffer: ByteBuffer) {
        check(!finished)
        val size = buffer.remaining()
        if (size % (channels * 2) != 0) throw IOException("truncated PCM frame")
        if (44 + pcmBytes + size + tailBytes > maxBytes) throw IOException("decoded audio exceeds byte limit")
        val chunk = ByteArray(minOf(size, 8192))
        while (buffer.hasRemaining()) {
            val count = minOf(chunk.size, buffer.remaining())
            buffer.get(chunk, 0, count)
            output.write(chunk, 0, count)
        }
        pcmBytes += size
    }

    fun finish() {
        check(!finished)
        if (pcmBytes == 0L) throw IOException("empty decoded audio")
        val silence = ByteArray(8192)
        var remaining = tailBytes
        while (remaining > 0) {
            val count = minOf(remaining, silence.size.toLong()).toInt()
            output.write(silence, 0, count)
            remaining -= count
        }
        val dataSize = pcmBytes + tailBytes
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataSize).toInt())
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1)
        header.putShort(channels.toShort()).putInt(sampleRate).putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize.toInt())
        output.seek(0)
        output.write(header.array())
        finished = true
    }

    override fun close() = output.close()
}
