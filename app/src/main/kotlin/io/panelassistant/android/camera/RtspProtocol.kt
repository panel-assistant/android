package io.panelassistant.android.camera

/**
 * The RTSP 1.0 control protocol for live H.264 and optional AAC tracks, with no sockets in it. `CameraRtspServer`
 * reads requests off a connection and feeds them through an [RtspSession]; everything a client can
 * observe — status codes, the state a method is valid in, the SDP, the transport it is granted — is
 * decided here so it can be unit-tested and mutation-proven.
 *
 * Scope is exactly what stock Home Assistant (go2rtc, ffmpeg/PyAV) needs from a camera and no more:
 * OPTIONS, DESCRIBE, SETUP over interleaved TCP, PLAY, PAUSE, GET_PARAMETER as keepalive, TEARDOWN. A
 * UDP transport request is answered `461 Unsupported Transport`, which both target clients treat as
 * "try TCP". There is no authentication: the panel's control plane is LAN-trust by design.
 */
class RtspRequest(val method: String, val url: String, val headers: Map<String, String>) {
    val cseq: Int? = headers["cseq"]?.toIntOrNull()

    fun header(name: String): String? = headers[name.lowercase()]

    /** The path of the request URL without scheme, authority or query. */
    val path: String get() = pathOf(url)

    companion object {
        /** The header block up to and excluding the blank line; null when the request line is not RTSP. */
        fun parse(headerBlock: String): RtspRequest? {
            val lines = headerBlock.split("\r\n", "\n").filter { it.isNotBlank() }
            val requestLine = lines.firstOrNull() ?: return null
            val parts = requestLine.trim().split(Regex("\\s+"))
            if (parts.size != 3 || !parts[2].startsWith("RTSP/")) return null
            val headers = HashMap<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            return RtspRequest(parts[0].uppercase(), parts[1], headers)
        }

        fun pathOf(url: String): String {
            val afterScheme = url.substringAfter("://", "")
            val path = if (afterScheme.isEmpty()) url else afterScheme.substringAfter('/', "").let { "/$it" }
            return path.substringBefore('?').substringBefore('#').ifEmpty { "/" }
        }

        /** `host[:port]` from an absolute RTSP URL, or null for a relative one. */
        fun authorityOf(url: String): String? {
            val afterScheme = url.substringAfter("://", "")
            if (afterScheme.isEmpty()) return null
            return afterScheme.substringBefore('/').substringBefore('?').ifEmpty { null }
        }
    }
}

class RtspResponse(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val head = buildString {
            append("RTSP/1.0 ").append(status).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return if (body == null) head else head + body
    }
}

/** What DESCRIBE (or a SETUP that arrives first) gets back from the camera: a description, or a classified refusal. */
sealed interface Described {
    data class Ready(val sdp: String) : Described
    data class Refused(val reason: CameraRefusal) : Described
}

/** The camera side of a session: attach on the first describing request, detach when the session ends. */
interface StreamDescriber {
    /** Attach this session to the camera for [request] if it is not attached yet, and describe the track. */
    fun describe(request: StreamRequest): Described
}

/**
 * One client's RTSP state machine. [handle] returns the response to write plus what the connection
 * must do next; it never writes anything itself.
 */
class RtspSession(
    val id: String,
    private val describer: StreamDescriber,
    private val mountPath: String = MOUNT_PATH,
) {
    enum class State { INIT, READY, PLAYING }

    class Outcome(
        val response: RtspResponse,
        /** The client is now receiving RTP on [rtpChannel]. */
        val startPlaying: Boolean = false,
        val stopPlaying: Boolean = false,
        /** The connection is finished after this response. */
        val close: Boolean = false,
    )

    var state: State = State.INIT
        private set
    var rtpChannel: Int = 0
        private set
    private data class Track(val url: String, val rtp: Int, val rtcp: Int)
    private val tracks = LinkedHashMap<Int, Track>()
    val audioRtpChannel: Int? get() = tracks[1]?.rtp
    val videoRtcpChannel: Int? get() = tracks[0]?.rtcp
    val audioRtcpChannel: Int? get() = tracks[1]?.rtcp

    /** [nextSequence] and [rtpTimestamp] describe the first packet PLAY will deliver, for `RTP-Info`. */
    fun handle(request: RtspRequest, nextSequence: Int = 0, rtpTimestamp: Long = 0L): Outcome {
        val cseq = request.cseq ?: return Outcome(respond(400, "Bad Request", request), close = true)
        val onMount = onMount(request.path)
        return when (request.method) {
            "OPTIONS" -> Outcome(respond(200, "OK", request, PUBLIC))
            "DESCRIBE" -> describe(request, onMount)
            "SETUP" -> setup(request, onMount)
            "PLAY" -> play(request, nextSequence, rtpTimestamp)
            "PAUSE" -> pause(request)
            "GET_PARAMETER", "SET_PARAMETER" -> {
                if (!sessionMatches(request)) Outcome(respond(454, "Session Not Found", request))
                else Outcome(respond(200, "OK", request, session()))
            }
            "TEARDOWN" -> {
                val matched = sessionMatches(request)
                val response = if (matched) respond(200, "OK", request, session()) else respond(454, "Session Not Found", request)
                stopPlaying()
                Outcome(response, stopPlaying = true, close = true)
            }
            else -> Outcome(respond(405, "Method Not Allowed", request, PUBLIC))
        }.also { check(it.response.headers.any { h -> h.first == "CSeq" && h.second == cseq.toString() }) }
    }

    private fun describe(request: RtspRequest, onMount: Boolean): Outcome {
        if (!onMount) return Outcome(respond(404, "Not Found", request))
        val accept = request.header("accept")
        if (accept != null && !accept.contains("application/sdp")) return Outcome(respond(406, "Not Acceptable", request))
        return when (val described = describer.describe(StreamRequest.fromUrl(request.url))) {
            is Described.Refused -> Outcome(respond(503, "Service Unavailable", request, listOf(CAMERA_HEADER to described.reason.token)))
            is Described.Ready -> Outcome(
                respond(
                    200, "OK", request,
                    listOf(
                        "Content-Base" to contentBase(request),
                        "Content-Type" to "application/sdp",
                    ),
                    body = described.sdp.toByteArray(Charsets.US_ASCII),
                ),
            )
        }
    }

    private fun setup(request: RtspRequest, onMount: Boolean): Outcome {
        if (!onMount) return Outcome(respond(404, "Not Found", request))
        if (state == State.PLAYING) return Outcome(respond(455, "Method Not Valid in This State", request, session()))
        if (state == State.READY && !sessionMatches(request)) return Outcome(respond(454, "Session Not Found", request))
        val transport = request.header("transport") ?: return Outcome(respond(400, "Bad Request", request))
        val interleaved = interleavedChannels(transport)
            ?: return Outcome(respond(461, "Unsupported Transport", request))
        // The camera attaches on the first describing request; a client that skips DESCRIBE attaches here.
        val track = when (request.path.trimEnd('/')) {
            mountPath, "$mountPath/$TRACK_CONTROL" -> 0
            "$mountPath/$AUDIO_TRACK_CONTROL" -> 1
            else -> return Outcome(respond(404, "Not Found", request))
        }
        val description = when (val described = describer.describe(StreamRequest.fromUrl(request.url))) {
            is Described.Refused -> return Outcome(respond(503, "Service Unavailable", request, listOf(CAMERA_HEADER to described.reason.token)))
            is Described.Ready -> described.sdp
        }
        if (track == 1 && !description.lineSequence().any { it.trim() == "a=control:$AUDIO_TRACK_CONTROL" }) {
            return Outcome(respond(404, "Not Found", request))
        }
        if (interleaved.first == interleaved.second || tracks.any { (id, existing) ->
                id != track && (existing.rtp in listOf(interleaved.first, interleaved.second) || existing.rtcp in listOf(interleaved.first, interleaved.second))
            }) return Outcome(respond(461, "Unsupported Transport", request))
        tracks[track] = Track(request.url, interleaved.first, interleaved.second)
        if (track == 0) rtpChannel = interleaved.first
        state = State.READY
        return Outcome(
            respond(
                200, "OK", request,
                session() + listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=${interleaved.first}-${interleaved.second}"),
            ),
        )
    }

    private fun play(request: RtspRequest, nextSequence: Int, rtpTimestamp: Long): Outcome {
        if (state == State.INIT || tracks[0] == null) return Outcome(respond(455, "Method Not Valid in This State", request))
        if (!sessionMatches(request)) return Outcome(respond(454, "Session Not Found", request))
        val wasPlaying = state == State.PLAYING
        state = State.PLAYING
        val headers = session() + listOf(
            "Range" to "npt=now-",
            "RTP-Info" to "url=${tracks.getValue(0).url};seq=$nextSequence;rtptime=$rtpTimestamp",
        )
        return Outcome(respond(200, "OK", request, headers), startPlaying = !wasPlaying)
    }

    private fun pause(request: RtspRequest): Outcome {
        if (state == State.INIT) return Outcome(respond(455, "Method Not Valid in This State", request))
        if (!sessionMatches(request)) return Outcome(respond(454, "Session Not Found", request))
        val wasPlaying = stopPlaying()
        return Outcome(respond(200, "OK", request, session()), stopPlaying = wasPlaying)
    }

    private fun stopPlaying(): Boolean {
        val wasPlaying = state == State.PLAYING
        if (wasPlaying) state = State.READY
        return wasPlaying
    }

    private fun sessionMatches(request: RtspRequest): Boolean {
        val given = request.header("session")?.substringBefore(';')?.trim() ?: return state == State.INIT
        return given == id
    }

    private fun session(): List<Pair<String, String>> = listOf("Session" to "$id;timeout=$SESSION_TIMEOUT_S")

    private fun onMount(path: String): Boolean {
        val trimmed = path.trimEnd('/')
        return trimmed == mountPath || trimmed.startsWith("$mountPath/")
    }

    private fun contentBase(request: RtspRequest): String {
        val authority = RtspRequest.authorityOf(request.url) ?: "127.0.0.1"
        return "rtsp://$authority$mountPath/"
    }

    private fun respond(
        status: Int,
        reason: String,
        request: RtspRequest,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): RtspResponse {
        val all = ArrayList<Pair<String, String>>()
        request.cseq?.let { all += "CSeq" to it.toString() }
        all += "Server" to SERVER_NAME
        all += headers
        return RtspResponse(status, reason, all, body)
    }

    companion object {
        const val MOUNT_PATH = "/live"
        const val TRACK_CONTROL = "trackID=0"
        const val AUDIO_TRACK_CONTROL = "trackID=1"
        const val SESSION_TIMEOUT_S = 60
        const val SERVER_NAME = "ha-paneld"
        /** Names the classified refusal on a 503 so a person reading a client log learns which gate held. */
        const val CAMERA_HEADER = "X-Camera"
        val PUBLIC: List<Pair<String, String>> = listOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER")

        /**
         * `interleaved=a-b` from a TCP transport spec, defaulting to 0-1; null for any other lower transport
         * or for a channel that does not fit the one byte the interleaved frame header carries.
         */
        fun interleavedChannels(transport: String): Pair<Int, Int>? {
            val spec = transport.split(',').map { it.trim() }.firstOrNull { it.uppercase().startsWith("RTP/AVP/TCP") } ?: return null
            val channels = spec.split(';').map { it.trim() }.firstOrNull { it.startsWith("interleaved=") }
                ?.removePrefix("interleaved=") ?: return 0 to 1
            val a = channels.substringBefore('-').toIntOrNull() ?: return null
            val b = channels.substringAfter('-', "").toIntOrNull() ?: (a + 1)
            if (a !in 0..MAX_CHANNEL || b !in 0..MAX_CHANNEL) return null
            return a to b
        }

        /** The largest channel an interleaved frame can name: its header spends one byte on it. */
        const val MAX_CHANNEL = 255

        /**
         * A session id from a random value: ten ASCII decimal digits, whatever the panel's locale. It
         * doubles as the SDP's sess-id, which RFC 4566 defines as a numeric string and strict parsers
         * (pion, used by go2rtc) enforce — a locale whose digits are not ASCII would break both.
         */
        fun sessionIdFrom(random: Int): String = String.format(java.util.Locale.ROOT, "%010d", random and 0x7FFF_FFFF)
    }
}

/** The live video track, with AAC only when microphone capture can be admitted. */
object Sdp {
    private const val CRLF = "\r\n"

    fun video(sessionId: String, sets: ParameterSets, fps: Int, width: Int, height: Int, audio: Boolean = false): String = buildString {
        append("v=0").append(CRLF)
        append("o=- ").append(sessionId).append(" 1 IN IP4 0.0.0.0").append(CRLF)
        append("s=ha-paneld camera").append(CRLF)
        append("c=IN IP4 0.0.0.0").append(CRLF)
        append("t=0 0").append(CRLF)
        append("a=tool:ha-paneld").append(CRLF)
        append("a=range:npt=now-").append(CRLF)
        append("a=control:*").append(CRLF)
        append("m=video 0 RTP/AVP ").append(RtpH264Packetizer.PAYLOAD_TYPE).append(CRLF)
        append("a=rtpmap:").append(RtpH264Packetizer.PAYLOAD_TYPE).append(" H264/").append(RtpH264Packetizer.CLOCK_RATE).append(CRLF)
        append("a=fmtp:").append(RtpH264Packetizer.PAYLOAD_TYPE)
            .append(" packetization-mode=1;profile-level-id=").append(sets.profileLevelId)
            .append(";sprop-parameter-sets=").append(sets.spropParameterSets()).append(CRLF)
        append("a=framerate:").append(fps).append(CRLF)
        append("a=x-dimensions:").append(width).append(',').append(height).append(CRLF)
        append("a=control:").append(RtspSession.TRACK_CONTROL).append(CRLF)
        if (audio) {
            append("m=audio 0 RTP/AVP 97").append(CRLF)
            append("a=rtpmap:97 MPEG4-GENERIC/16000/1").append(CRLF)
            append("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;config=1408;SizeLength=13;IndexLength=3;IndexDeltaLength=3").append(CRLF)
            append("a=control:").append(RtspSession.AUDIO_TRACK_CONTROL).append(CRLF)
        }
    }
}
