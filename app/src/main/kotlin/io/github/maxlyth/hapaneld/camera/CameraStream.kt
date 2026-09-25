package io.github.maxlyth.hapaneld.camera

/**
 * What the encoder was bound to for the running session — the first stream client's parameters after
 * the profile caps — plus the parameter sets a joining client needs before its first frame.
 */
data class StreamParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val kbps: Int,
    val encoder: String,
    val sets: ParameterSets,
)

sealed interface StreamAdmission {
    /**
     * Close [lease] exactly once when the client is gone; closing the last one stops the encoder or the
     * camera. [session] is the camera session generation the lease belongs to, which a stream end names.
     */
    class Granted(val lease: AutoCloseable, val params: StreamParams, val session: Long) : StreamAdmission
    data class Refused(val reason: CameraRefusal) : StreamAdmission
}

/** The camera as the transport sees it: a blocking attach that binds or joins the encode, and a sync-frame nudge. */
interface CameraStreamSource {
    /** Blocking; never call on the main thread. Returns once the encoder has parameter sets or a refusal. */
    fun acquireStream(request: StreamRequest): StreamAdmission

    /** A client just started playing: ask the encoder for an IDR so it decodes without waiting for the next one. */
    fun requestKeyFrame()
}

data class StreamTransportFacts(
    /** The port the transport is listening on, or null while it is not. */
    val port: Int?,
    /** Clients currently attached to the camera through this transport. */
    val clients: Int,
)

/**
 * The transport as the camera owner drives it. Listening follows the master switch; frames arrive on
 * the owner's camera thread and must never block it — a slow client is the transport's problem to
 * shed: the stream yields, the dashboard does not.
 *
 * The owner decides whose each effect is under its own lock, but calls the ones that can drop a client
 * outside it, because a dropped client closes its lease and that re-enters the owner. A newer session
 * or encoder attempt can therefore take over between the decision and the call, so each such effect
 * names its session or attempt, and the transport applies it to that and to nothing newer.
 */
interface CameraStreamTransport {
    fun setListening(on: Boolean)

    /** Terminal teardown: the listener and every client. Called from the service drain. */
    fun stop()

    fun facts(): StreamTransportFacts

    /**
     * Encoder [attempt] (re)started and produced its parameter sets; they are advertised until that
     * attempt stops or a newer one publishes. The owner calls this under its own lock, in the critical
     * section that checks the attempt, so it must neither block nor call back into the camera.
     */
    fun onParameterSets(sets: ParameterSets, attempt: Long)

    /**
     * One encoded access unit from encoder [attempt], as Annex-B NAL units without start codes. Delivered
     * only while [attempt]'s parameter sets are the advertised ones: any other is a superseded codec's.
     */
    fun onAccessUnit(nals: List<ByteArray>, keyFrame: Boolean, ptsUs: Long, attempt: Long)

    /**
     * Encoder [attempt] stopped but the session may come back (a bounded reopen): clients keep their
     * place. Retracts [attempt]'s parameter sets, never a newer attempt's.
     */
    fun onEncoderStopped(attempt: Long)

    /**
     * The encode is over for every stream client granted at session generation [through] or earlier —
     * the session closed, degraded, or the encoder itself failed: drop them so they reconnect and pay
     * the open cost, after any hold the camera imposes. A client granted by a newer session, or one whose
     * admission is still in progress, belongs to whichever session admits it and stays.
     */
    fun onStreamEnded(through: Long)
}

/** A transport for a board with no camera: never listens, never has clients. */
object AbsentStreamTransport : CameraStreamTransport {
    override fun setListening(on: Boolean) = Unit
    override fun stop() = Unit
    override fun facts(): StreamTransportFacts = StreamTransportFacts(port = null, clients = 0)
    override fun onParameterSets(sets: ParameterSets, attempt: Long) = Unit
    override fun onAccessUnit(nals: List<ByteArray>, keyFrame: Boolean, ptsUs: Long, attempt: Long) = Unit
    override fun onEncoderStopped(attempt: Long) = Unit
    override fun onStreamEnded(through: Long) = Unit
}

/**
 * Delivered rate over a sliding window, from the encoder's output rather than from what was asked for:
 * the plan's open question is whether the targets are honoured, and this is the measurement.
 */
class StreamStats(private val windowMs: Long = 5_000L) {
    private val at = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Int>()

    @Synchronized
    fun onFrame(nowMs: Long, size: Int) {
        at.addLast(nowMs)
        bytes.addLast(size)
        prune(nowMs)
    }

    @Synchronized
    fun reset() {
        at.clear()
        bytes.clear()
    }

    /** Frames per second over the window, or null until two frames have been seen. */
    @Synchronized
    fun fps(nowMs: Long): Double? {
        prune(nowMs)
        if (at.size < 2) return null
        val span = (at.last() - at.first()).coerceAtLeast(1L)
        return (at.size - 1) * 1_000.0 / span
    }

    /** Kilobits per second over the window, or null until two frames have been seen. */
    @Synchronized
    fun kbps(nowMs: Long): Int? {
        prune(nowMs)
        if (at.size < 2) return null
        val span = (at.last() - at.first()).coerceAtLeast(1L)
        val bits = bytes.drop(1).sumOf { it.toLong() } * 8
        return (bits / span).toInt()
    }

    private fun prune(nowMs: Long) {
        while (at.isNotEmpty() && nowMs - at.first() > windowMs) {
            at.removeFirst()
            bytes.removeFirst()
        }
    }
}
