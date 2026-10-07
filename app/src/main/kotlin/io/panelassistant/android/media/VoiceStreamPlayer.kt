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

/** The native calls the voice player makes: [SendspinNative] on a panel, a fake in tests. */
internal interface SendspinApi {
    val available: Boolean
    fun create(stateDir: String, name: String, softwareVersion: String, sampleRate: Int, channels: Int, requiredLeadMs: Int): Long
    fun clientId(handle: Long): String
    fun connect(handle: Long, url: String)
    fun loop(handle: Long)
    fun nextEvent(handle: Long): Int
    fun serverId(handle: Long): String?
    fun read(handle: Long, buffer: ByteBuffer, maxBytes: Int, waitMs: Int): Int
    fun played(handle: Long, frames: Int, finishUs: Long)
    fun nowUs(): Long
    fun destroy(handle: Long)
    fun writeRecord(stateDir: String, serverId: String, psk: ByteArray): Boolean
    fun takeOutbound(handle: Long, timeoutMs: Int): ByteArray?
    fun deliver(handle: Long, transportId: Int, type: Int, data: ByteArray?, receiveUs: Long)
}

/** The JNI surface of `libhapaneld_sendspin.so` (app/src/main/cpp/sendspin). */
internal object SendspinNative : SendspinApi {
    /** True once the library loaded; attempted on first use, never at class init. */
    override val available: Boolean by lazy {
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

    override fun create(stateDir: String, name: String, softwareVersion: String, sampleRate: Int, channels: Int, requiredLeadMs: Int) =
        nCreate(stateDir, name, softwareVersion, sampleRate, channels, requiredLeadMs)
    override fun clientId(handle: Long) = nClientId(handle)
    override fun connect(handle: Long, url: String) = nConnect(handle, url)
    override fun loop(handle: Long) = nLoop(handle)
    override fun nextEvent(handle: Long) = nNextEvent(handle)
    override fun serverId(handle: Long) = nServerId(handle)
    override fun read(handle: Long, buffer: ByteBuffer, maxBytes: Int, waitMs: Int) = nRead(handle, buffer, maxBytes, waitMs)
    override fun played(handle: Long, frames: Int, finishUs: Long) = nPlayed(handle, frames, finishUs)
    override fun nowUs() = nNowUs()
    override fun destroy(handle: Long) = nDestroy(handle)
    override fun writeRecord(stateDir: String, serverId: String, psk: ByteArray) = nWriteRecord(stateDir, serverId, psk)
    override fun takeOutbound(handle: Long, timeoutMs: Int) = nTakeOutbound(handle, timeoutMs)
    override fun deliver(handle: Long, transportId: Int, type: Int, data: ByteArray?, receiveUs: Long) =
        nDeliver(handle, transportId, type, data, receiveUs)

    const val EVENT_STREAM_START = 1
    const val EVENT_STREAM_END = 2
    const val EVENT_TRUST_USER = 3
    const val EVENT_TRUST_NONE = 4
}

/**
 * One live native client. Threads other than the library's loop thread (the stream writers and the socket
 * pump) make every native call through [call]; [destroy] marks the client gone, waits for any such call in
 * flight to return, and only then frees it. A call after that is refused and returns its fallback, so a
 * writer that outlives its session can never reach freed native state.
 */
internal class SendspinHandle(private val raw: Long, private val free: (Long) -> Unit) {
    private val lock = Object()
    private var users = 0
    private var destroyed = false

    fun <T> call(refused: T, block: (Long) -> T): T {
        synchronized(lock) {
            if (destroyed) return refused
            users++
        }
        try {
            return block(raw)
        } finally {
            synchronized(lock) { if (--users == 0) lock.notifyAll() }
        }
    }

    fun destroy() {
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            while (users > 0) lock.wait()
        }
        free(raw)
    }
}

/** The speaker one stream plays on: an [AudioTrack] on a panel, a recorder in tests. */
internal interface VoiceOutput {
    fun play()

    /** Writes without blocking; returns the bytes taken (0 while the buffer is full) or a negative error. */
    fun write(data: ByteArray, offset: Int, length: Int): Int
    fun pause()
    fun flush()
    fun stop()
    fun release()

    /** Frames played so far. */
    fun playedFrames(): Long

    /** The monotonic time at which the last of the [written] frames will have been presented. */
    fun finishNs(written: Long, rate: Int): Long
}

/** [VoiceOutput] on an [AudioTrack] with the attributes today's speech uses, so the same volume applies. */
internal class AudioTrackOutput(rate: Int, channels: Int) : VoiceOutput {
    private val stamp = AudioTimestamp()
    private val track: AudioTrack = run {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
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
            .setBufferSizeInBytes(maxOf(minBuffer, rate / 10 * channels * 2))
            .build()
    }

    override fun play() = track.play()
    override fun write(data: ByteArray, offset: Int, length: Int) =
        track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun stop() = track.stop()
    override fun release() = track.release()
    override fun playedFrames() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    override fun finishNs(written: Long, rate: Int): Long =
        if (track.getTimestamp(stamp) && stamp.framePosition > 0) {
            stamp.nanoTime + (written - stamp.framePosition) * NANOS_PER_SECOND / rate
        } else {
            System.nanoTime() + (written - track.playbackHeadPosition.toLong()) * NANOS_PER_SECOND / rate
        }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
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
 * The speaker is opened only for a stream some announcement may still own: `stream/start` opens one
 * output at the stream's rate, and `stream/end` lets what was written play out, then releases it. A stream
 * [VoiceStreamClaims] has dropped (its announcement was cancelled, or fell back to the URL before it arrived)
 * never opens the speaker. While it plays, the presentation time of every buffer goes back to the library,
 * which aligns the audio to Panel Assistant's clock.
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
    private val native: SendspinApi = SendspinNative,
    private val openOutput: (rate: Int, channels: Int) -> VoiceOutput = ::AudioTrackOutput,
    private val writerJoinMs: Long = WRITER_JOIN_MS,
) : PanelAssistantVoiceStreamPeer {
    private val loop = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sendspin-loop") }

    /** Bumped by every [open] and [close]; a session's loop runs while the value it started with is current. */
    private val session = AtomicLong()

    @Volatile private var identity: String? = null
    private var identityResolved = false

    @Synchronized
    override fun clientId(): String? {
        if (identityResolved) return identity
        if (!native.available) {
            identityResolved = true
            return null
        }
        // start() provisions the keypair on first run; client_id() is readable only on the loop thread.
        identity = runCatching {
            loop.submit<String?> {
                stateDir.mkdirs()
                val handle = native.create(stateDir.path, name(), softwareVersion, outputRate(), CHANNELS, 0)
                if (handle == 0L) return@submit null
                try {
                    native.clientId(handle).takeIf { it.length == CLIENT_ID_CHARS }
                } finally {
                    native.destroy(handle)
                }
            }.get()
        }.onFailure { Log.w(TAG, "voice stream identity unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        // A failed start is retried at the next hello rather than withholding the stream until a restart.
        identityResolved = identity != null
        return identity
    }

    override fun open(url: String, grant: PanelAssistantVoiceStreamGrant) {
        if (!native.available) return
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
            native.writeRecord(stateDir.path, grant.serverId, psk)
        } finally {
            psk.fill(0)
        }
        if (!written) {
            Log.w(TAG, "voice stream pairing record was not stored")
            return
        }
        val rate = outputRate()
        val raw = native.create(stateDir.path, name(), softwareVersion, rate, CHANNELS, 0)
        if (raw == 0L) {
            Log.w(TAG, "voice stream client did not start")
            return
        }
        val handle = SendspinHandle(raw, native::destroy)
        val writers = mutableListOf<StreamWriter>()
        var current: StreamWriter? = null
        var verified = false
        val wire = object : SendspinWire {
            override fun take(timeoutMs: Int) = handle.call(null) { native.takeOutbound(it, timeoutMs) }
            override fun deliver(transportId: Int, type: Int, data: ByteArray?, receiveUs: Long) =
                handle.call(Unit) { native.deliver(it, transportId, type, data, receiveUs) }
            override fun nowUs() = native.nowUs()
        }
        val socketScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val pump = socketScope.launch { SendspinSocketPump(wire, sockets).run() }
        try {
            native.connect(raw, url)
            Log.i(TAG, "voice stream connecting to Panel Assistant")
            while (session.get() == mine) {
                native.loop(raw)
                while (true) {
                    val event = native.nextEvent(raw)
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
                    val reached = native.serverId(raw)
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
            // Writers never block on the speaker, so once aborted they leave within a buffer. One that has
            // not left by the deadline is not assumed gone: the handle waits for any native call it is in,
            // then refuses its later ones, so destroying the client cannot free state it still uses.
            writers.forEach { it.abort() }
            val stopping = writers.filter { it.join(writerJoinMs); it.isAlive }
            if (stopping.isNotEmpty()) Log.w(TAG, "voice output still stopping (${stopping.size}); its native calls are refused")
            runBlocking { pump.cancelAndJoin() }
            socketScope.cancel()
            handle.destroy()
            Log.i(TAG, "voice stream disconnected")
        }
    }

    /**
     * Feeds one stream to the speaker. Reads the library's aligned PCM, writes it to its own output and
     * reports each buffer's presentation time; writes short silence (unreported) while the library has
     * nothing, so the output's timeline stays live through the stream. A dropped stream is read and
     * discarded, reported as presented now, so the library is never left waiting; one dropped before its
     * first buffer never opens the speaker.
     */
    private inner class StreamWriter(
        private val handle: SendspinHandle,
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
            var output: VoiceOutput? = null
            try {
                val buffer = ByteBuffer.allocateDirect(rate / 50 * frameBytes) // up to 20 ms
                val bytes = ByteArray(buffer.capacity())
                val silence = ByteArray(rate / 100 * frameBytes) // 10 ms
                var written = 0L
                var silenced = false
                while (!finishing) {
                    val read = handle.call(0) { native.read(it, buffer, buffer.capacity(), READ_WAIT_MS) }
                    val frames = read / frameBytes
                    if (claims.dropped(id)) {
                        if (!silenced) {
                            silenced = true
                            output?.pause()
                            output?.flush()
                        }
                        if (frames > 0) handle.call(Unit) { native.played(it, frames, native.nowUs()) }
                        buffer.clear()
                        continue
                    }
                    val speaker = output ?: openOutput(rate, channels).also {
                        output = it
                        it.play()
                    }
                    if (frames <= 0) {
                        writeFully(speaker, silence, silence.size)
                        written += silence.size / frameBytes
                        continue
                    }
                    buffer.get(bytes, 0, read)
                    buffer.clear()
                    writeFully(speaker, bytes, read)
                    written += frames
                    val finishNs = speaker.finishNs(written, rate)
                    handle.call(Unit) { native.played(it, frames, finishNs / 1_000L) }
                }
                val speaker = output
                if (speaker != null && !aborted && !silenced) {
                    // A short silent tail keeps the output open through the last syllable, then what was
                    // written plays out before the speaker is released.
                    val tail = ByteArray(rate / 1_000 * TAIL_MS * frameBytes)
                    writeFully(speaker, tail, tail.size)
                    written += tail.size / frameBytes
                    speaker.stop()
                    val deadline = System.nanoTime() + DRAIN_LIMIT_MS * 1_000_000L
                    while (speaker.playedFrames() < written && System.nanoTime() < deadline && !aborted) {
                        sleep(DRAIN_POLL_MS)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "voice stream output failed: ${error.javaClass.simpleName}")
            } finally {
                output?.let { runCatching { it.release() } }
                claims.ended(id)
            }
        }

        /** Writes without blocking, waiting a moment while the output is full, so [abort] is seen at once. */
        private fun writeFully(output: VoiceOutput, data: ByteArray, length: Int) {
            var offset = 0
            while (offset < length && !aborted) {
                val count = output.write(data, offset, length - offset)
                if (count < 0) return
                if (count == 0) sleep(WRITE_POLL_MS) else offset += count
            }
        }
    }

    companion object {
        const val TAG = "ha-paneld/voice-stream"
        private const val CHANNELS = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val CLIENT_ID_CHARS = 43
        private const val READ_WAIT_MS = 2
        private const val WRITE_POLL_MS = 2L
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

        /** The service's player: state beside the app's files, named for the device, at its native rate. */
        fun forService(context: android.content.Context, softwareVersion: String) = VoiceStreamPlayer(
            stateDir = File(context.filesDir, "sendspin"),
            name = { android.os.Build.MODEL.orEmpty().ifBlank { "Panel" } },
            softwareVersion = softwareVersion,
            outputRate = { nativeOutputRate(context.getSystemService(AudioManager::class.java)) },
        )
    }
}
