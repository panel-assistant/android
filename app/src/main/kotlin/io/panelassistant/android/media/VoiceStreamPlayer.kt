package io.panelassistant.android.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamGrant
import io.panelassistant.android.panelassistant.PanelAssistantVoiceStreamPeer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The native calls the voice player makes: [SendspinNative] on a panel, a fake in tests. */
internal interface SendspinApi {
    val available: Boolean
    fun create(stateDir: String, name: String, softwareVersion: String, requiredLeadMs: Int): Long
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

    /** The library's next request: `[type, transport id (4 bytes, big-endian), payload]`, or null after [timeoutMs]. */
    fun takeOutbound(handle: Long, timeoutMs: Int): ByteArray?

    /** The connection [transportId] opened, received a message or closed ([type]: OPEN, TEXT, BINARY, CLOSE). */
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

    @JvmStatic external fun nCreate(stateDir: String, name: String, softwareVersion: String, requiredLeadMs: Int): Long
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

    override fun create(stateDir: String, name: String, softwareVersion: String, requiredLeadMs: Int) =
        nCreate(stateDir, name, softwareVersion, requiredLeadMs)
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

    /** Bridge request and delivery types (`transport/bridge.h`). */
    const val OPEN = 1
    const val TEXT = 2
    const val BINARY = 3
    const val CLOSE = 4
}

/**
 * One live native client. Threads other than the library's loop thread (the stream writers and the session
 * link) make every native call through [call]; [destroy] marks the client gone, waits for any such call in
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

    /**
     * The monotonic time at which the last of the [written] frames will have been presented, or null while
     * the output cannot tell yet.
     */
    fun finishNs(written: Long, rate: Int): Long?
}

/**
 * When frames written to an output will be presented, from the output's own timestamps.
 *
 * The library aligns every chunk against the times it is told, and treats a step of 5 ms or more as lost
 * sync: it cuts audio or inserts silence. So this tells nothing until two successive timestamps agree with
 * each other at the stream's rate, and then only times on that one timeline. A cold output has no
 * timestamp at first, and Android's first one is often a placeholder (the current time against a position
 * that has not moved yet); estimating meanwhile, without the output's latency, cut 130-140 ms of speech
 * early in most replies on a Sonoff panel when the real timestamps arrived. A reading that disagrees with the one
 * before it is not followed, and the last agreeing one is extrapolated instead. An output that never gives
 * an agreeing pair within [NO_TIMESTAMP_LIMIT_NS] falls back to the playback head, so it still plays.
 */
internal class PresentationClock(private val rate: Int) {
    private var lastFrames = -1L
    private var lastNs = 0L
    private var anchorFrames = -1L
    private var anchorNs = 0L
    private var firstNs = -1L

    /**
     * When the last of [written] frames will have been presented, given the output's latest timestamp
     * ([stampFrames] presented at [stampNs], or null when it has none), its playback head and the time now.
     */
    fun finishNs(written: Long, stampFrames: Long?, stampNs: Long, headFrames: Long, nowNs: Long): Long? {
        if (firstNs < 0) firstNs = nowNs
        if (stampFrames != null) {
            if (stampFrames > lastFrames && lastFrames >= 0 &&
                kotlin.math.abs(stampNs - (lastNs + framesToNs(stampFrames - lastFrames))) <= AGREE_NS
            ) {
                anchorFrames = stampFrames
                anchorNs = stampNs
            }
            lastFrames = stampFrames
            lastNs = stampNs
        }
        return when {
            anchorFrames >= 0 -> anchorNs + framesToNs(written - anchorFrames)
            nowNs - firstNs >= NO_TIMESTAMP_LIMIT_NS -> nowNs + framesToNs(written - headFrames)
            else -> null
        }
    }

    private fun framesToNs(frames: Long) = frames * NANOS_PER_SECOND / rate

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Below the library's 5 ms hard-sync threshold. */
        const val AGREE_NS = 2_000_000L
        /** Inside the stream's lead: a time first told this late still comes before its first audio. */
        const val NO_TIMESTAMP_LIMIT_NS = 400_000_000L
    }
}

/** [VoiceOutput] on an [AudioTrack] with the attributes today's speech uses, so the same volume applies. */
internal class AudioTrackOutput(rate: Int, channels: Int) : VoiceOutput {
    private val stamp = AudioTimestamp()
    private val clock = PresentationClock(rate)
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
    override fun finishNs(written: Long, rate: Int): Long? {
        val stamped = track.getTimestamp(stamp) && stamp.framePosition > 0
        return clock.finishNs(written, stamp.framePosition.takeIf { stamped }, stamp.nanoTime, playedFrames(), System.nanoTime())
    }
}

/**
 * The panel's synchronised voice player: a Sendspin client (the vendored sendspin-cpp, player role only)
 * that Panel Assistant alone can drive, carried inside the Panel Assistant session.
 *
 * It has no listener, no socket of its own and no unpaired access. Each session that grants
 * `voice_stream_session` writes that server's pairing record into the client's app-private store and
 * connects the client over the session: the library's outbound messages go to the session's sender, and
 * the `sendspin` frames Panel Assistant sends on the session are handed to the library in session order,
 * never dropped or reordered (the Noise counters depend on it). The client checks that the server it
 * reached is the one granted; the session's end disconnects it. Its keypair is generated and kept by the
 * library in the same store.
 *
 * Whatever the library renders plays: the speaker opens when a stream has audio, `stream/end` lets what
 * was written play out, and the speaker is released. While it plays, the presentation time of every
 * buffer goes back to the library, which aligns the audio to Panel Assistant's clock. [mute] silences the
 * stream playing now (and any that starts while muted) until [unmute].
 *
 * One thread runs the library's main loop (the library requires its start, loop and stop on one thread),
 * one carries outbound requests, one delivers inbound frames, and one per stream feeds the speaker.
 */
internal class VoiceStreamPlayer(
    private val stateDir: File,
    private val name: () -> String,
    private val softwareVersion: String,
    private val native: SendspinApi = SendspinNative,
    private val openOutput: (rate: Int, channels: Int) -> VoiceOutput = ::AudioTrackOutput,
    private val writerJoinMs: Long = WRITER_JOIN_MS,
) : PanelAssistantVoiceStreamPeer {
    private val loop = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sendspin-loop") }

    /** Bumped by every [open] and [close]; a session's loop runs while the value it started with is current. */
    private val session = AtomicLong()

    /** Frames Panel Assistant sent on the current session, waiting for the library: (is text, bytes, received at). */
    @Volatile private var inbound: LinkedBlockingQueue<Inbound>? = null

    @Volatile private var muted = false

    /** The stream playing now; replaced under [streams] so a [mute] cannot miss a stream starting. */
    @Volatile private var current: StreamWriter? = null
    private val streams = Any()

    private class Inbound(val text: Boolean, val data: ByteArray, val receiveUs: Long)

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
                val handle = native.create(stateDir.path, name(), softwareVersion, 0)
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

    override fun open(grant: PanelAssistantVoiceStreamGrant, closed: () -> Unit, send: (frame: ByteArray, text: Boolean) -> Unit) {
        if (!native.available) return
        val mine = session.incrementAndGet()
        val queue = LinkedBlockingQueue<Inbound>()
        inbound = queue
        muted = false
        loop.execute { runSession(mine, grant, queue, closed, send) }
    }

    /** Called on the session loop, in session order; stamped now, delivered by the inbound thread. */
    override fun receive(frame: ByteArray, text: Boolean) {
        inbound?.add(Inbound(text, frame, native.nowUs()))
    }

    override fun mute() {
        synchronized(streams) {
            muted = true
            current?.silence()
        }
    }

    override fun unmute() {
        muted = false
    }

    override fun close() {
        session.incrementAndGet()
        inbound = null
    }

    private fun runSession(mine: Long, grant: PanelAssistantVoiceStreamGrant, queue: LinkedBlockingQueue<Inbound>, closed: () -> Unit, send: (ByteArray, Boolean) -> Unit) {
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
        val raw = native.create(stateDir.path, name(), softwareVersion, REQUIRED_LEAD_MS)
        if (raw == 0L) {
            Log.w(TAG, "voice stream client did not start")
            return
        }
        val handle = SendspinHandle(raw, native::destroy)
        val writers = mutableListOf<StreamWriter>()
        var verified = false
        val link = SessionLink(handle, mine, queue, closed, send)
        try {
            link.start()
            native.connect(raw, SESSION_URL)
            Log.i(TAG, "voice stream connecting to Panel Assistant over the session")
            while (session.get() == mine) {
                native.loop(raw)
                while (true) {
                    val event = native.nextEvent(raw)
                    if (event == 0) break
                    when (event and 0xF) {
                        SendspinNative.EVENT_STREAM_START -> {
                            current?.finish()
                            val streamRate = (event ushr 8).takeIf { it > 0 } ?: DEFAULT_RATE
                            val channels = ((event ushr 4) and 0xF).takeIf { it > 0 } ?: CHANNELS
                            val writer = synchronized(streams) {
                                StreamWriter(handle, streamRate, channels).also {
                                    if (muted) it.silence()
                                    current = it
                                }
                            }
                            writers += writer
                            writer.start()
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
            current = null
            // Writers never block on the speaker, so once aborted they leave within a buffer. One that has
            // not left by the deadline is not assumed gone: the handle waits for any native call it is in,
            // then refuses its later ones, so destroying the client cannot free state it still uses.
            writers.forEach { it.abort() }
            val stopping = writers.filter { it.join(writerJoinMs); it.isAlive }
            if (stopping.isNotEmpty()) Log.w(TAG, "voice output still stopping (${stopping.size}); its native calls are refused")
            link.stop()
            handle.destroy()
            Log.i(TAG, "voice stream disconnected")
        }
    }

    /**
     * Carries the library's connection over the session. The outbound thread takes the library's requests:
     * an open is answered at once (the session is already up), each message goes to the session's sender in
     * order, and a close is reported back. The inbound thread hands each frame Panel Assistant sent to the
     * library in the order the session received it; a delivery may wait while the library's inbound buffer
     * is full, which holds back only this queue, never the session loop.
     */
    private inner class SessionLink(
        private val handle: SendspinHandle,
        private val mine: Long,
        private val queue: LinkedBlockingQueue<Inbound>,
        private val closed: () -> Unit,
        private val send: (ByteArray, Boolean) -> Unit,
    ) {
        @Volatile private var transportId = 0
        private val live get() = session.get() == mine

        private val outbound = Thread({
            while (live) {
                val request = handle.call(null) { native.takeOutbound(it, TAKE_TIMEOUT_MS) } ?: continue
                if (request.size < HEADER_BYTES) continue
                val type = request[0].toInt()
                val id = ((request[1].toInt() and 0xFF) shl 24) or ((request[2].toInt() and 0xFF) shl 16) or
                    ((request[3].toInt() and 0xFF) shl 8) or (request[4].toInt() and 0xFF)
                when (type) {
                    SendspinNative.OPEN -> {
                        transportId = id
                        handle.call(Unit) { native.deliver(it, id, SendspinNative.OPEN, null, 0L) }
                    }
                    SendspinNative.TEXT, SendspinNative.BINARY -> if (id == transportId) {
                        send(request.copyOfRange(HEADER_BYTES, request.size), type == SendspinNative.TEXT)
                    }
                    SendspinNative.CLOSE -> if (id == transportId) {
                        transportId = 0
                        handle.call(Unit) { native.deliver(it, id, SendspinNative.CLOSE, null, 0L) }
                        Log.i(TAG, "voice stream connection closed by the player")
                        closed()
                    }
                }
            }
        }, "sendspin-out")

        private val inbound = Thread({
            try {
                while (live) {
                    val frame = queue.poll(TAKE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) ?: continue
                    val id = transportId
                    if (id == 0) {
                        Log.w(TAG, "voice stream frame before the connection opened; not delivered")
                        continue
                    }
                    val type = if (frame.text) SendspinNative.TEXT else SendspinNative.BINARY
                    handle.call(Unit) { native.deliver(it, id, type, frame.data, frame.receiveUs) }
                }
            } catch (_: InterruptedException) {
                // Stopping.
            }
        }, "sendspin-in")

        fun start() {
            outbound.start()
            inbound.start()
        }

        /** Called once the session is no longer current: both threads leave within one poll. */
        fun stop() {
            inbound.interrupt()
            outbound.join(writerJoinMs)
            inbound.join(writerJoinMs)
        }
    }

    /**
     * Feeds one stream to the speaker. Reads the library's aligned PCM, writes it to its own output and
     * reports each buffer's presentation time once the output can tell it; frames written before then are
     * reported together with the first time it gives, and meanwhile the library keeps priming the output
     * with silence. The output opens with the stream's first audio, and once open, short silence
     * (unreported) keeps its timeline live while the library has nothing. A silenced stream is read and
     * discarded, reported as presented now, so the library is never left waiting.
     */
    private inner class StreamWriter(
        private val handle: SendspinHandle,
        private val rate: Int,
        private val channels: Int,
    ) : Thread("sendspin-audio") {
        @Volatile private var finishing = false
        @Volatile private var aborted = false
        @Volatile private var silenced = false

        fun finish() {
            finishing = true
        }

        fun abort() {
            aborted = true
            finishing = true
        }

        /** Stop playing this stream at once; the rest of it is discarded. */
        fun silence() {
            silenced = true
        }

        override fun run() {
            val frameBytes = channels * BYTES_PER_SAMPLE
            var output: VoiceOutput? = null
            var stopped = false
            try {
                val buffer = ByteBuffer.allocateDirect(rate / 50 * frameBytes) // up to 20 ms
                val bytes = ByteArray(buffer.capacity())
                val silence = ByteArray(rate / 100 * frameBytes) // 10 ms
                var written = 0L
                var unreported = 0
                var timed = false
                while (!finishing) {
                    val read = handle.call(0) { native.read(it, buffer, buffer.capacity(), READ_WAIT_MS) }
                    val frames = read / frameBytes
                    if (silenced) {
                        if (!stopped) {
                            stopped = true
                            output?.pause()
                            output?.flush()
                        }
                        if (frames > 0) handle.call(Unit) { native.played(it, frames, native.nowUs()) }
                        buffer.clear()
                        continue
                    }
                    if (frames <= 0) {
                        output?.let {
                            writeFully(it, silence, silence.size)
                            written += silence.size / frameBytes
                        }
                        continue
                    }
                    val speaker = output ?: openOutput(rate, channels).also {
                        output = it
                        it.play()
                    }
                    buffer.get(bytes, 0, read)
                    buffer.clear()
                    if (timed || !silent(bytes, read)) {
                        writeFully(speaker, bytes, read)
                        written += frames
                    } else {
                        // Until it is told a time, the library sends only priming silence, faster than
                        // real time and for as long as it waits; what the output cannot take at once is
                        // dropped (and reported played), so none of it queues ahead of the reply.
                        written += speaker.write(bytes, 0, read).coerceAtLeast(0) / frameBytes
                    }
                    unreported += frames
                    val finishNs = speaker.finishNs(written, rate) ?: continue
                    timed = true
                    handle.call(Unit) { native.played(it, unreported, finishNs / 1_000L) }
                    unreported = 0
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
            }
        }

        private fun silent(data: ByteArray, length: Int): Boolean {
            for (i in 0 until length) if (data[i] != 0.toByte()) return false
            return true
        }

        /** Writes without blocking, waiting a moment while the output is full, so [abort] is seen at once. */
        private fun writeFully(output: VoiceOutput, data: ByteArray, length: Int) {
            var offset = 0
            while (offset < length && !aborted && !silenced) {
                val count = output.write(data, offset, length - offset)
                if (count < 0) return
                if (count == 0) sleep(WRITE_POLL_MS) else offset += count
            }
        }
    }

    companion object {
        const val TAG = "ha-paneld/voice-stream"

        /** The URL the library logs for its one connection; nothing dials it. */
        private const val SESSION_URL = "panel-assistant://session"
        private const val CHANNELS = 1
        private const val DEFAULT_RATE = 24_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CLIENT_ID_CHARS = 43
        private const val READ_WAIT_MS = 2
        private const val WRITE_POLL_MS = 2L
        private const val STREAMING_LOOP_MS = 10L
        private const val IDLE_LOOP_MS = 50L
        private const val TAIL_MS = 200

        /**
         * The lead the player asks Panel Assistant for between a stream's start and its first audio, so
         * the first audio is placed only once the output can say when it plays. Measured on 2026-10-16
         * (Shelly X2i, Sonoff NSPanel 86): the stream reached the panel up to about 190 ms after Panel
         * Assistant started it, its output gave agreeing timestamps 190-340 ms after that, then held
         * 216-230 ms ahead of now, and the library adds 50 ms of startup silence: 630-790 ms in six traced
         * starts on the X2i, one more over 800 ms. Below the lead, the start of the reply is cut.
         */
        const val REQUIRED_LEAD_MS = 1_000
        private const val DRAIN_LIMIT_MS = 1_500L
        private const val DRAIN_POLL_MS = 10L
        private const val WRITER_JOIN_MS = 2_000L
        private const val TAKE_TIMEOUT_MS = 100
        private const val HEADER_BYTES = 5

        /** The service's player: state beside the app's files, named for the device. */
        fun forService(context: android.content.Context, softwareVersion: String) = VoiceStreamPlayer(
            stateDir = File(context.filesDir, "sendspin"),
            name = { android.os.Build.MODEL.orEmpty().ifBlank { "Panel" } },
            softwareVersion = softwareVersion,
        )
    }
}
