package io.github.maxlyth.hapaneld.camera

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * The RTSP transport: a plain TCP listener on [port] whose clients each get an [RtspSession] and,
 * once they PLAY, a per-client RTP packetiser fed from the one encode session. Pure JVM so it runs
 * under the unit tests; Android supplies nothing it needs.
 *
 * Ownership is the camera's, never the client's: a client's first describing request takes a stream lease from the
 * camera and its disconnect gives it back, so the camera and encoder open on the first client and
 * close on the last. Threads — one acceptor, and one reader plus one writer per client — are all owned
 * here and all torn down by [stop] or by the client going away; the service drain calls [stop] before
 * the camera owner stops.
 *
 * A slow client is shed, never waited for: each client has a bounded packet queue, and a client whose
 * queue overflows is disconnected on the encoder's thread without that thread ever blocking on a
 * socket: the stream yields, the dashboard does not. A client that stops talking is dropped by the
 * read timeout, so a dead TCP peer cannot hold the camera open.
 */
class CameraRtspServer(
    private val port: Int = DEFAULT_PORT,
    private val source: () -> CameraStreamSource,
    private val maxClients: Int = MAX_CLIENTS,
    private val log: (String) -> Unit = {},
    private val queuePackets: Int = QUEUE_PACKETS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    /** Accepted connections of any state, attached or not; enforced before a connection owns a thread. */
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    /**
     * Runs on a client's reader thread just after a DESCRIBE has adopted its grant and before the
     * DESCRIBE returns; a seam for tests that place a stream end exactly there. Production passes nothing.
     */
    private val afterAdoption: () -> Unit = {},
    private val audio: CameraAudioSource? = null,
    /** Same monotonic origin as encoder PTS; Android supplies elapsed realtime. */
    private val clockUs: () -> Long = { System.nanoTime() / 1_000L },
    private val wallMs: () -> Long = System::currentTimeMillis,
) : CameraStreamTransport {

    private val lock = Any()
    private val random = SecureRandom()
    /** The listener's own state: the socket it bound, or null while it is binding or after it failed. */
    private var listener: Listener? = null
    private val clients = LinkedHashSet<Client>()
    /** Clients between their capacity check and their lease, so two simultaneous DESCRIBEs cannot both pass. */
    private var attaching = 0
    private var nextClientId = 1
    private var stopped = false
    private val audioLock = Any()
    private val audioEpoch = java.util.concurrent.atomic.AtomicLong()
    private var audioLease: AutoCloseable? = null

    /** Foreign capture calls never run under the client lock. One STREAM lease serves all viewers. */
    private fun refreshAudio() = synchronized(audioLock) {
        val wanted = synchronized(lock) { !stopped && advertised.get() != null && clients.any { it.playing && it.session.audioRtpChannel != null } }
        if (!wanted) {
            synchronized(lock) { audioEpoch.incrementAndGet() }
            val retiring = audioLease
            audioLease = null
            runCatching { retiring?.close() }
        } else if (audioLease == null) {
            val epoch = synchronized(lock) { audioEpoch.incrementAndGet() }
            val started = runCatching { audio?.start { unit, ptsUs ->
                val targets = synchronized(lock) {
                    if (audioEpoch.get() == epoch) clients.filter { it.playing && it.session.audioRtpChannel != null } else emptyList()
                }
                targets.forEach { it.sendAudio(unit, ptsUs, epoch) }
            } }.onFailure { log("camera audio could not start: ${it.javaClass.simpleName}") }.getOrNull()
            if (audioEpoch.get() == epoch) audioLease = started else runCatching { started?.close() }
        }
    }

    /**
     * The parameter sets a DESCRIBE is answered with and the encoder attempt that published them, as
     * one value so neither can be read without the other. The attempt is what makes each encoder effect
     * safe to apply outside the camera's lock: output from any other attempt is a superseded codec's.
     */
    private class Advertised(val sets: ParameterSets, val attempt: Long)

    private val advertised = AtomicReference<Advertised?>(null)
    @Volatile private var lastRtpTimestamp = 0L

    /** The port actually bound, for tests that listen on 0; waits briefly for the bind to settle. */
    val boundPort: Int?
        get() {
            val current = synchronized(lock) { listener } ?: return null
            return runCatching { current.bound.get(BIND_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
        }

    override fun setListening(on: Boolean) {
        if (on) startListening() else stopListening()
    }

    /**
     * Binding happens on the acceptor thread, never on the caller's: the owner flips the switch from
     * whichever thread delivered the configuration change, which may be Android's main thread.
     */
    private fun startListening() {
        synchronized(lock) {
            if (stopped || listener != null) return
            listener = Listener().also { it.thread.start() }
        }
    }

    private fun stopListening(joinMs: Long = 0L) {
        val current: Listener?
        val open: List<Client>
        synchronized(lock) {
            current = listener
            listener = null
            open = clients.toList()
        }
        current?.close()
        open.forEach { it.close() }
        if (joinMs > 0) current?.let { runCatching { it.thread.join(joinMs) } }
    }

    override fun stop() {
        synchronized(lock) { stopped = true }
        stopListening(joinMs = JOIN_MS)
        refreshAudio()
    }

    override fun facts(): StreamTransportFacts = synchronized(lock) {
        StreamTransportFacts(port = listener?.current, clients = clients.count { it.attached })
    }

    private inner class Listener {
        /** Settled by the acceptor thread: the bound port, or null when the bind failed. */
        val bound = java.util.concurrent.CompletableFuture<Int?>()
        val thread = Thread(::run, "camera-rtsp-accept").apply { isDaemon = true }
        private var socket: ServerSocket? = null

        /** The bound port right now, without waiting; null while binding or after a failure. */
        val current: Int? get() = bound.getNow(null)

        private fun run() {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(this@CameraRtspServer.port))
                }
            } catch (e: IOException) {
                log("rtsp listener could not bind port $port: ${e.javaClass.simpleName}")
                synchronized(lock) { if (listener === this) listener = null }
                bound.complete(null)
                return
            }
            val closedEarly = synchronized(lock) {
                if (listener !== this) true else { socket = server; false }
            }
            if (closedEarly) {
                runCatching { server.close() }
                bound.complete(null)
                return
            }
            bound.complete(server.localPort)
            acceptLoop(this, server)
        }

        fun close() {
            val s = synchronized(lock) { socket }
            s?.let { runCatching { it.close() } }
            bound.complete(null)
        }
    }

    override fun onParameterSets(sets: ParameterSets, attempt: Long) {
        advertised.set(Advertised(sets, attempt))
        refreshAudio()
    }

    override fun onAccessUnit(nals: List<ByteArray>, keyFrame: Boolean, ptsUs: Long, attempt: Long) {
        // The camera checked this unit's attempt and then let go of its lock, so a newer attempt may
        // have published since: only the attempt whose sets are advertised reaches a client.
        val current = advertised.get()?.takeIf { it.attempt == attempt } ?: return
        val wire = accessUnitForTransport(nals, current.sets)
        if (wire.isEmpty()) return
        val timestamp = RtpH264Packetizer.rtpTimestamp(ptsUs)
        lastRtpTimestamp = timestamp
        val playing = synchronized(lock) { clients.filter { it.playing } }
        playing.forEach { it.send(wire, timestamp, ptsUs) }
    }

    override fun onEncoderStopped(attempt: Long) {
        // A bounded reopen: the clients keep their place and receive fresh parameter sets with the next
        // IDR. Until then nothing is advertised: a DESCRIBE in the gap is refused rather than answered
        // with the previous encoder's sets. Only [attempt]'s own sets are retracted: a newer encoder
        // may have published between the camera deciding to retract and this call.
        advertised.updateAndGet { current -> current?.takeUnless { it.attempt == attempt } }
        // The owner may remove its indication during a bounded camera reopen. No microphone
        // capture crosses that gap; fresh video sets restart audio only after indication returns.
        refreshAudio()
    }

    override fun onStreamEnded(through: Long) {
        // Chosen under the lock that adopts a client's lease, so the choice is exact: a client granted
        // by a newer session is not this ending's to drop, and one still inside its DESCRIBE is decided
        // by the answer it gets. Everything else goes, as it always has.
        val ended = synchronized(lock) { clients.filter { it.endsWith(through) } }
        ended.forEach { it.close() }
    }

    private fun acceptLoop(owner: Listener, socket: ServerSocket) {
        while (!socket.isClosed) {
            val accepted = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            // The admission boundary: a connection beyond the bound is closed here, before it owns a
            // reader, a writer or a queue — so idle or half-open peers cannot multiply threads, and the
            // attached-stream limit further down bounds only what reaches the camera.
            val client = synchronized(lock) {
                if (listener !== owner || clients.size >= maxConnections) null
                else Client(accepted, nextClientId++).also { clients += it }
            }
            if (client == null) {
                runCatching { accepted.close() }
                continue
            }
            client.start()
        }
    }

    private fun sessionId(): String = RtspSession.sessionIdFrom(random.nextInt())

    /** One connection: its RTSP session, its lease on the camera, and its two threads. */
    private inner class Client(private val socket: Socket, id: Int) : StreamDescriber {
        val session = RtspSession(sessionId(), this)
        private val packetizer = RtpH264Packetizer(ssrc = random.nextInt(), firstSequence = random.nextInt(0x10000))
        private val audioPacketizer = RtpAacPacketizer(random.nextInt(), random.nextInt(0x10000))
        private val videoRtcp = RtcpSender(packetizer.ssrc)
        private val audioRtcp = RtcpSender(audioPacketizer.ssrc)
        private val queue = ArrayBlockingQueue<ByteArray>(queuePackets)
        private val reader = Thread(::readLoop, "camera-rtsp-client-$id").apply { isDaemon = true }
        private val writer = Thread(::writeLoop, "camera-rtsp-send-$id").apply { isDaemon = true }
        private var lease: AutoCloseable? = null
        private var params: StreamParams? = null
        private var closed = false
        /** The camera session generation that granted [lease]; null while the client holds none. */
        private var grantedIn: Long? = null
        /**
         * True from the capacity check until the camera's answer is adopted or refused, and cleared in the
         * critical section that decides that answer: from then on the client is judged by its grant, so a
         * stream end that arrives before the DESCRIBE returns cannot be recorded and then forgotten.
         */
        private var describing = false
        /**
         * The latest stream end that arrived while this client was describing. Whose client it is cannot
         * be known until the camera answers, so the end is kept and applied to the answer: a grant at or
         * before it, or a refusal, is the ended session's and drops the client as the end always has.
         */
        private var endedWhileDescribing: Long? = null

        /**
         * Under [lock]: whether a stream end through generation [through] drops this client now. A client
         * still describing is decided when its answer arrives, so the end is recorded for it instead.
         */
        fun endsWith(through: Long): Boolean {
            if (describing) {
                endedWhileDescribing = maxOf(endedWhileDescribing ?: through, through)
                return false
            }
            return grantedIn.let { it == null || it <= through }
        }

        /** Under [lock]: the camera has answered; stop recording ends and hand back the latest one recorded. */
        private fun answeredLocked(): Long? {
            describing = false
            return endedWhileDescribing.also { endedWhileDescribing = null }
        }

        @Volatile var playing = false
            private set

        val attached: Boolean get() = synchronized(lock) { lease != null }

        fun start() {
            runCatching {
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
            }
            // The writer exists before the reader can finish a request: a graceful end waits for the
            // writer to drain the final response, and a join on a thread that has not started yet
            // returns at once, closing the socket with that response still queued.
            writer.start()
            reader.start()
        }

        override fun describe(request: StreamRequest): Described {
            synchronized(lock) { params }?.let { return sdpOrRefusal(it) }
            val full = synchronized(lock) {
                if (clients.count { it.attached } + attaching >= maxClients) true else { attaching++; describing = true; false }
            }
            if (full) return Described.Refused(CameraRefusal.BUSY)
            try {
                return when (val admission = source().acquireStream(request)) {
                    is StreamAdmission.Refused -> {
                        if (synchronized(lock) { answeredLocked() } != null) close()
                        Described.Refused(admission.reason)
                    }
                    is StreamAdmission.Granted -> {
                        val adopted = synchronized(lock) {
                            val ended = answeredLocked()
                            if (closed || (ended != null && admission.session <= ended)) {
                                false
                            } else {
                                lease = admission.lease
                                params = admission.params
                                grantedIn = admission.session
                                true
                            }
                        }
                        if (!adopted) {
                            runCatching { admission.lease.close() }
                            close()
                            Described.Refused(CameraRefusal.STOPPING)
                        } else {
                            afterAdoption()
                            sdpOrRefusal(admission.params)
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    attaching--
                    describing = false
                    endedWhileDescribing = null
                }
            }
        }

        /**
         * Describe the RUNNING encoder's parameter sets, never a retained pair: the camera publishes a
         * new encoder's sets here before it wakes anyone, and clears them when the encoder stops, so a
         * DESCRIBE that finds none is one that arrived while no encoder runs and is refused instead.
         */
        private fun sdpOrRefusal(params: StreamParams): Described {
            val current = advertised.get()?.sets ?: return Described.Refused(CameraRefusal.STARVED)
            return Described.Ready(Sdp.video(session.id, current, params.fps, params.width, params.height, audio = audio?.available() == true))
        }

        /** From the encoder's thread: packetise for this client and queue; overflow drops the client, never blocks. */
        fun send(wire: List<ByteArray>, rtpTimestamp: Long, ptsUs: Long) {
            val channel = session.rtpChannel
            for (packet in packetizer.packetize(wire, rtpTimestamp)) {
                videoRtcp.sent(packet)
                if (!offerOrDrop(RtspInterleaved.frame(channel, packet))) {
                    log("rtsp client dropped: it cannot keep up with the stream")
                    close()
                    return
                }
            }
            if (session.audioRtpChannel != null) session.videoRtcpChannel?.let { channelRtcp ->
                videoRtcp.report(rtpTimestamp, ptsUs, clockUs(), wallMs())?.let { enqueue(RtspInterleaved.frame(channelRtcp, it)) }
            }
        }

        fun sendAudio(unit: ByteArray, ptsUs: Long, epoch: Long) {
            val accepted = synchronized(lock) {
                if (closed || !playing || audioEpoch.get() != epoch) return
                val channel = session.audioRtpChannel ?: return
                val timestamp = RtpAacPacketizer.rtpTimestamp(ptsUs)
                var fits = true
                for (packet in audioPacketizer.packetize(unit, timestamp)) {
                    audioRtcp.sent(packet)
                    if (!offerOrDrop(RtspInterleaved.frame(channel, packet))) { fits = false; break }
                }
                if (fits) session.audioRtcpChannel?.let { channelRtcp ->
                    audioRtcp.report(timestamp, ptsUs, clockUs(), wallMs())?.let {
                        fits = offerOrDrop(RtspInterleaved.frame(channelRtcp, it))
                    }
                }
                fits
            }
            // A close enters capture lifetime management, so never call it under the client lock.
            if (!accepted) close()
        }

        /** Never waits: a full queue means this client is behind, and the encoder's thread is not the one to pay for it. */
        private fun offerOrDrop(bytes: ByteArray): Boolean = queue.offer(bytes)

        private fun readLoop() {
            // A protocol-level end (TEARDOWN, a malformed request) lets the writer deliver the final
            // response before the socket closes; a transport-level end closes at once.
            var graceful = false
            try {
                val input = BufferedInputStream(socket.getInputStream())
                while (true) {
                    val head = readRequestHead(input) ?: break
                    val request = RtspRequest.parse(head)
                    if (request == null) {
                        enqueue(RtspResponse(400, "Bad Request", listOf("Server" to RtspSession.SERVER_NAME)).encode())
                        graceful = true
                        break
                    }
                    val bodyLength = request.header("content-length")?.toIntOrNull() ?: 0
                    if (bodyLength < 0 || bodyLength > maxBodyBytes) {
                        // Part of the admission boundary: a body beyond the bound is never read or held.
                        enqueue(RtspResponse(413, "Request Entity Too Large", listOf("CSeq" to (request.cseq?.toString() ?: "0"), "Server" to RtspSession.SERVER_NAME)).encode())
                        graceful = true
                        break
                    }
                    if (bodyLength > 0) skip(input, bodyLength)
                    val outcome = session.handle(request, packetizer.nextSequence, lastRtpTimestamp)
                    if (outcome.stopPlaying) {
                        playing = false
                        refreshAudio()
                    }
                    // The 200 PLAY is queued BEFORE media delivery is enabled, so on the one ordered
                    // byte stream the response always precedes the first interleaved frame; the sync
                    // frame is requested right after so the picture the client needs is on its way.
                    enqueue(outcome.response.encode())
                    if (outcome.startPlaying) {
                        playing = true
                        source().requestKeyFrame()
                        refreshAudio()
                    }
                    if (outcome.close) {
                        graceful = true
                        break
                    }
                }
            } catch (_: IOException) {
                // Timeout, reset or our own close: every one of these ends the client the same way.
            } finally {
                // Let the writer drain the final response, but only for a bounded time: a peer that
                // sends TEARDOWN and then stops reading must not keep the lease through a stalled write.
                if (graceful && queue.offer(POISON)) runCatching { writer.join(DRAIN_MS) }
                close()
            }
        }

        private fun writeLoop() {
            try {
                val output = socket.getOutputStream()
                while (true) {
                    val bytes = queue.take()
                    if (bytes === POISON) break
                    output.write(bytes)
                }
                output.flush()
            } catch (_: IOException) {
                // The socket is gone; close() below settles everything else.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                close()
            }
        }

        private fun enqueue(bytes: ByteArray) {
            if (!offerOrDrop(bytes)) close()
        }

        /** Idempotent; safe from any thread, including the encoder's. */
        fun close() {
            val toRelease: AutoCloseable?
            synchronized(lock) {
                if (closed) return
                closed = true
                playing = false
                toRelease = lease
                lease = null
                params = null
                grantedIn = null
                clients.remove(this)
            }
            runCatching { socket.close() }
            queue.clear()
            queue.offer(POISON)
            refreshAudio()
            toRelease?.let { runCatching { it.close() } }
        }

        /**
         * The next request's header block, or null at end of stream. Interleaved frames from the client
         * (RTCP receiver reports on the data channel) are consumed and ignored, as RFC 2326 allows.
         */
        private fun readRequestHead(input: InputStream): String? {
            while (true) {
                input.mark(1)
                val first = input.read()
                if (first < 0) return null
                if (first == RtspInterleaved.MAGIC.toInt()) {
                    input.read()
                    val hi = input.read()
                    val lo = input.read()
                    if (hi < 0 || lo < 0) return null
                    skip(input, (hi shl 8) or lo)
                    continue
                }
                input.reset()
                val head = ByteArrayOutputStream()
                var matched = 0
                while (matched < 4) {
                    val b = input.read()
                    if (b < 0) return null
                    head.write(b)
                    if (head.size() > MAX_HEAD_BYTES) throw IOException("request too large")
                    matched = when {
                        b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                        b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                        b == '\r'.code -> 1
                        else -> 0
                    }
                }
                return head.toString(Charsets.US_ASCII.name())
            }
        }

        private fun skip(input: InputStream, count: Int) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining.toLong())
                if (skipped <= 0) {
                    if (input.read() < 0) throw IOException("eof in body")
                    remaining--
                } else {
                    remaining -= skipped.toInt()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8554
        const val MOUNT = RtspSession.MOUNT_PATH
        const val MAX_CLIENTS = 4
        /** Attached streams plus a little room for a client that is still describing or tearing down. */
        const val MAX_CONNECTIONS = 8
        /** RTSP request bodies here are keepalive or parameter probes; anything larger is not a client we serve. */
        const val MAX_BODY_BYTES = 16 * 1024
        /** About two seconds of a 2 Mbps stream in 1400-byte packets; beyond it the client is not keeping up. */
        const val QUEUE_PACKETS = 512
        /** One and a half times the advertised session timeout: a peer that sends nothing for this long is gone. */
        const val READ_TIMEOUT_MS = RtspSession.SESSION_TIMEOUT_S * 1_500
        private const val JOIN_MS = 2_000L
        private const val BIND_WAIT_MS = 2_000L
        private const val DRAIN_MS = 2_000L
        private const val MAX_HEAD_BYTES = 16 * 1024
        private val POISON = ByteArray(0)
    }
}
