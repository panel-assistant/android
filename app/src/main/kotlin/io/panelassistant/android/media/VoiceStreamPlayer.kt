package io.panelassistant.android.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamGrant
import io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamPeer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The JNI surface of `libhapaneld_sendspin.so` (app/src/main/cpp/sendspin). */
internal object SendspinNative {
    /** True once the library loaded; attempted on first use, never at class init. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("hapaneld_sendspin")
            true
        } catch (error: Throwable) {
            Log.i(VoiceStreamPlayer.TAG, "libhapaneld_sendspin not loadable on this ABI: no synchronised voice", error)
            false
        }
    }

    @JvmStatic external fun nCreate(stateDir: String, name: String, softwareVersion: String, sampleRate: Int, channels: Int, requiredLeadMs: Int): Long
    @JvmStatic external fun nClientId(handle: Long): String
    @JvmStatic external fun nConnect(handle: Long, url: String)
    @JvmStatic external fun nLoop(handle: Long)
    @JvmStatic external fun nNextEvent(handle: Long): Int
    @JvmStatic external fun nServerId(handle: Long): String?
    @JvmStatic external fun nRead(handle: Long, buffer: ByteBuffer, maxBytes: Int, waitMs: Int): Int
    @JvmStatic external fun nPlayed(handle: Long, frames: Int, finishUs: Long)
    @JvmStatic external fun nNowUs(): Long
    @JvmStatic external fun nDestroy(handle: Long)
    @JvmStatic external fun nWriteRecord(stateDir: String, serverId: String, psk: ByteArray): Boolean
    @JvmStatic external fun nTakeOutbound(handle: Long, timeoutMs: Int): ByteArray?
    @JvmStatic external fun nDeliver(handle: Long, transportId: Int, type: Int, data: ByteArray?, receiveUs: Long)

    const val EVENT_STREAM_START = 1
    const val EVENT_STREAM_END = 2
    const val EVENT_TRUST_USER = 3
    const val EVENT_TRUST_NONE = 4
}

/**
 * The panel's synchronised voice player: a Sendspin client (the vendored sendspin-cpp, player role only)
 * that Panel Assistant alone can drive.
 *
 * It has no listener of its own and no unpaired access. Each Panel Assistant session that grants
 * `voice_stream` writes that server's pairing record into the client's app-private store, connects
 * out to the session's own Home Assistant address, and checks the server it reached is the one granted;
 * the session's end disconnects it. Its keypair is generated and kept by the library in the same store.
 *
 * The speaker is opened only for a stream: `stream/start` opens one [AudioTrack] at the stream's rate,
 * with the attributes today's speech uses (so the same volume applies), and `stream/end` lets what was
 * written play out, then releases it. While it plays, the presentation time of every buffer
 * ([AudioTrack.getTimestamp]) goes back to the library, which aligns the audio to Panel Assistant's clock.
 *
 * One thread runs the library's main loop (the library requires its start, loop and stop on one thread);
 * one thread per stream feeds the speaker. [claims] tells announcements which stream is theirs.
 */
internal class VoiceStreamPlayer(
    private val stateDir: File,
    private val name: () -> String,
    private val softwareVersion: String,
    private val outputRate: () -> Int,
    val claims: VoiceStreamClaims = VoiceStreamClaims(),
    /** The app's own WebSocket client, which carries the stream with the panel's TLS trust. */
    private val sockets: SendspinSocketOpener = KtorSendspinSocketOpener(),
) : PanelAssistantVoiceStreamPeer {
    private val loop = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sendspin-loop") }

    /** Bumped by every [open] and [close]; a session's loop runs while the value it started with is current. */
    private val session = AtomicLong()

    @Volatile private var identity: String? = null
    private var identityResolved = false

    @Synchronized
    override fun clientId(): String? {
        if (identityResolved) return identity
        if (!SendspinNative.available) {
            identityResolved = true
            return null
        }
        // start() provisions the keypair on first run; client_id() is readable only on the loop thread.
        identity = runCatching {
            loop.submit<String?> {
                stateDir.mkdirs()
                val handle = SendspinNative.nCreate(stateDir.path, name(), softwareVersion, outputRate(), CHANNELS, 0)
                if (handle == 0L) return@submit null
                try {
                    SendspinNative.nClientId(handle).takeIf { it.length == CLIENT_ID_CHARS }
                } finally {
                    SendspinNative.nDestroy(handle)
                }
            }.get()
        }.onFailure { Log.w(TAG, "voice stream identity unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        // A failed start is retried at the next hello rather than withholding the stream until a restart.
        identityResolved = identity != null
        return identity
    }

    override fun open(url: String, grant: PanelAssistantVoiceStreamGrant) {
        if (!SendspinNative.available) return
        val mine = session.incrementAndGet()
        loop.execute { runSession(mine, url, grant) }
    }

    override fun close() {
        session.incrementAndGet()
    }

    private fun runSession(mine: Long, url: String, grant: PanelAssistantVoiceStreamGrant) {
        if (session.get() != mine) return
        val psk = grant.psk()
        val written = try {
            SendspinNative.nWriteRecord(stateDir.path, grant.serverId, psk)
        } finally {
            psk.fill(0)
        }
        if (!written) {
            Log.w(TAG, "voice stream pairing record was not stored")
            return
        }
        val rate = outputRate()
        val handle = SendspinNative.nCreate(stateDir.path, name(), softwareVersion, rate, CHANNELS, 0)
        if (handle == 0L) {
            Log.w(TAG, "voice stream client did not start")
            return
        }
        val writers = mutableListOf<StreamWriter>()
        var current: StreamWriter? = null
        var verified = false
        val wire = object : SendspinWire {
            override fun take(timeoutMs: Int) = SendspinNative.nTakeOutbound(handle, timeoutMs)
            override fun deliver(transportId: Int, type: Int, data: ByteArray?, receiveUs: Long) =
                SendspinNative.nDeliver(handle, transportId, type, data, receiveUs)
            override fun nowUs() = SendspinNative.nNowUs()
        }
        val socketScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val pump = socketScope.launch { SendspinSocketPump(wire, sockets).run() }
        try {
            SendspinNative.nConnect(handle, url)
            Log.i(TAG, "voice stream connecting to Panel Assistant")
            while (session.get() == mine) {
                SendspinNative.nLoop(handle)
                while (true) {
                    val event = SendspinNative.nNextEvent(handle)
                    if (event == 0) break
                    when (event and 0xF) {
                        SendspinNative.EVENT_STREAM_START -> {
                            current?.finish()
                            val streamRate = (event ushr 8).takeIf { it > 0 } ?: rate
                            val channels = ((event ushr 4) and 0xF).takeIf { it > 0 } ?: CHANNELS
                            current = StreamWriter(handle, claims.started(), streamRate, channels).also {
                                writers += it
                                it.start()
                            }
                        }
                        SendspinNative.EVENT_STREAM_END -> {
                            current?.finish()
                            current = null
                        }
                        SendspinNative.EVENT_TRUST_USER -> Log.i(TAG, "voice stream paired with Panel Assistant")
                        SendspinNative.EVENT_TRUST_NONE -> Log.w(TAG, "voice stream reached an unpaired server")
                    }
                }
                if (!verified) {
                    val reached = SendspinNative.nServerId(handle)
                    if (reached != null) {
                        if (reached != grant.serverId) {
                            Log.w(TAG, "voice stream reached a server other than the one Panel Assistant named; disconnecting")
                            break
                        }
                        verified = true
                        Log.i(TAG, "voice stream connected")
                    }
                }
                writers.removeAll { !it.isAlive }
                Thread.sleep(if (current != null) STREAMING_LOOP_MS else IDLE_LOOP_MS)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "voice stream session failed: ${error.javaClass.simpleName}")
        } finally {
            // The writers and the socket pump use the client, so they stop before it is destroyed.
            writers.forEach { it.abort() }
            writers.forEach { it.join(WRITER_JOIN_MS) }
            runBlocking { pump.cancelAndJoin() }
            socketScope.cancel()
            SendspinNative.nDestroy(handle)
            Log.i(TAG, "voice stream disconnected")
        }
    }

    /**
     * Feeds one stream to the speaker. Reads the library's aligned PCM, writes it to its own [AudioTrack]
     * and reports each buffer's presentation time; writes short silence (unreported) while the library has
     * nothing, so the track's timeline stays live through the stream. A dropped stream is read and
     * discarded, reported as presented now, so the library is never left waiting.
     */
    private inner class StreamWriter(
        private val handle: Long,
        private val id: Long,
        private val rate: Int,
        private val channels: Int,
    ) : Thread("sendspin-audio-$id") {
        @Volatile private var finishing = false
        @Volatile private var aborted = false

        fun finish() {
            finishing = true
        }

        fun abort() {
            aborted = true
            finishing = true
        }

        override fun run() {
            val frameBytes = channels * BYTES_PER_SAMPLE
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(mask).build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .setBufferSizeInBytes(maxOf(minBuffer, rate / 10 * frameBytes))
                    .build()
                track.play()
                val buffer = ByteBuffer.allocateDirect(rate / 50 * frameBytes) // up to 20 ms
                val bytes = ByteArray(buffer.capacity())
                val silence = ByteArray(rate / 100 * frameBytes) // 10 ms
                val stamp = AudioTimestamp()
                var written = 0L
                var silenced = false
                while (!finishing) {
                    val read = SendspinNative.nRead(handle, buffer, buffer.capacity(), READ_WAIT_MS)
                    val frames = read / frameBytes
                    if (claims.dropped(id)) {
                        if (!silenced) {
                            silenced = true
                            track.pause()
                            track.flush()
                        }
                        if (frames > 0) SendspinNative.nPlayed(handle, frames, SendspinNative.nNowUs())
                        buffer.clear()
                        continue
                    }
                    if (frames <= 0) {
                        writeFully(track, silence, silence.size)
                        written += silence.size / frameBytes
                        continue
                    }
                    buffer.get(bytes, 0, read)
                    buffer.clear()
                    writeFully(track, bytes, read)
                    written += frames
                    val finishNs = if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
                        stamp.nanoTime + (written - stamp.framePosition) * NANOS_PER_SECOND / rate
                    } else {
                        System.nanoTime() + (written - track.playbackHeadPosition.toLong()) * NANOS_PER_SECOND / rate
                    }
                    SendspinNative.nPlayed(handle, frames, finishNs / 1_000L)
                }
                if (!aborted && !silenced) {
                    // A short silent tail keeps the output open through the last syllable, then what was
                    // written plays out before the speaker is released.
                    val tail = ByteArray(rate / 1_000 * TAIL_MS * frameBytes)
                    writeFully(track, tail, tail.size)
                    written += tail.size / frameBytes
                    track.stop()
                    val deadline = System.nanoTime() + DRAIN_LIMIT_MS * 1_000_000L
                    while (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL < written &&
                        System.nanoTime() < deadline && !aborted
                    ) {
                        sleep(DRAIN_POLL_MS)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "voice stream output failed: ${error.javaClass.simpleName}")
            } finally {
                track?.let { runCatching { it.release() } }
                claims.ended(id)
            }
        }

        private fun writeFully(track: AudioTrack, data: ByteArray, length: Int) {
            var offset = 0
            while (offset < length && !aborted) {
                val count = track.write(data, offset, length - offset, AudioTrack.WRITE_BLOCKING)
                if (count <= 0) return
                offset += count
            }
        }
    }

    companion object {
        const val TAG = "ha-paneld/voice-stream"
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val CLIENT_ID_CHARS = 43
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val READ_WAIT_MS = 2
        private const val STREAMING_LOOP_MS = 10L
        private const val IDLE_LOOP_MS = 50L
        private const val TAIL_MS = 200
        private const val DRAIN_LIMIT_MS = 1_500L
        private const val DRAIN_POLL_MS = 10L
        private const val WRITER_JOIN_MS = 2_000L

        /** The device's native output rate, which the player asks Panel Assistant to stream at. */
        fun nativeOutputRate(audio: AudioManager?): Int =
            audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 8_000..192_000 }
                ?: AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 8_000..192_000 }
                ?: 48_000
    }
}
