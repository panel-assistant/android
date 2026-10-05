package io.panelassistant.android.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import io.panelassistant.android.audio.MicPurpose
import io.panelassistant.android.audio.MicrophoneSource
import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame

/** AAC is the audio codec Core HLS consumes; capture remains the service's shared PCM source. */
internal class AndroidCameraAudioSource(
    private val source: () -> MicrophoneSource,
    private val admitted: () -> Boolean,
    private val foreground: (Boolean) -> Boolean,
    private val log: (String, Throwable?) -> Unit,
) : CameraAudioSource {
    private fun format() = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
    }

    override fun available(): Boolean = admitted() && runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format()) != null
    }.getOrDefault(false)

    override fun start(output: (ByteArray, Long) -> Unit): AutoCloseable? {
        if (!available() || !foreground(true)) return null
        var codec: MediaCodec? = null
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            encoder.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val lock = Any()
            var closed = false
            var attaching = true
            var held: io.panelassistant.android.audio.MicLease? = null
            fun retire(message: String? = null, error: Throwable? = null) {
                val release = synchronized(lock) {
                    if (closed) return
                    closed = true
                    held.also { held = null } to !attaching
                }
                release.first?.close()
                synchronized(lock) {
                    runCatching { encoder.stop() }
                    runCatching { encoder.release() }
                }
                // A fatal first callback can precede lease() returning. That acquisition path closes
                // the newly handed-back lease before dropping standing, rather than orphaning it.
                if (release.second) foreground(false)
                if (message != null) log(message, error)
            }
            // Camera video uses elapsed realtime; source PCM timestamps use nanoTime. Sample the
            // relation once per encode so both tracks share a capture-time origin, including sleep.
            val originUs = SystemClock.elapsedRealtimeNanos() / 1_000L - System.nanoTime() / 1_000L
            val consumer = object : PcmConsumer {
                override fun onFrame(frame: PcmFrame) {
                    val units = ArrayList<Pair<ByteArray, Long>>()
                    var revoked = false
                    var failure: Exception? = null
                    synchronized(lock) {
                        if (closed) return
                        if (!admitted()) revoked = true
                        else try {
                            fun drain() {
                                val info = MediaCodec.BufferInfo()
                                while (true) {
                                    val index = encoder.dequeueOutputBuffer(info, 0)
                                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                                    if (index < 0) break
                                    try {
                                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                            encoder.getOutputBuffer(index)?.let { buffer ->
                                                buffer.position(info.offset)
                                                buffer.limit(info.offset + info.size)
                                                val bytes = ByteArray(info.size)
                                                buffer.get(bytes)
                                                units += bytes to info.presentationTimeUs
                                            }
                                        }
                                    } finally { encoder.releaseOutputBuffer(index, false) }
                                }
                            }
                            drain()
                            val input = encoder.dequeueInputBuffer(0)
                            if (input >= 0) {
                                val buffer = requireNotNull(encoder.getInputBuffer(input)).apply { clear(); order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                                if (frame.samples.size * 2 <= buffer.remaining()) {
                                    frame.samples.forEach { buffer.putShort(it) }
                                    encoder.queueInputBuffer(input, 0, buffer.position(), frame.timestampNs / 1_000L + originUs, 0)
                                } else encoder.queueInputBuffer(input, 0, 0, frame.timestampNs / 1_000L + originUs, 0)
                            }
                            drain()
                        } catch (error: Exception) { failure = error }
                    }
                    if (revoked || failure != null) {
                        retire(if (revoked) "Camera audio admission revoked; keeping video" else "AAC encode failed; keeping video", failure)
                        return
                    }
                    // Never call the transport under the codec lock: a slow client can close its
                    // lease here, and codec teardown must not wait behind that callback.
                    units.forEach { (bytes, ptsUs) -> output(bytes, ptsUs) }
                }
            }
            val lease = source().lease(MicPurpose.STREAM, consumer = consumer)
            val adopted = synchronized(lock) {
                attaching = false
                if (closed) false else { held = lease; true }
            }
            if (!adopted) {
                lease.close()
                foreground(false)
            }
            return AutoCloseable { retire() }
        } catch (error: Exception) {
            runCatching { codec?.release() }
            foreground(false)
            log("AAC capture could not start; keeping video", error)
            return null
        }
    }
}
