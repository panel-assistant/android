package io.panelassistant.android.camera

import io.panelassistant.android.camera.RtspSession.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control protocol as stock Home Assistant's clients drive it, decided without a socket: which
 * method is valid in which state, what a UDP request is told, when the camera is attached, and that
 * the description carries exactly one video track and no audio.
 */
class RtspProtocolTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
    private val sets = ParameterSets(sps, pps)
    private val sdp = Sdp.video("77", sets, fps = 15, width = 1280, height = 720)

    private class FakeDescriber(private var answer: Described) : StreamDescriber {
        val requests = ArrayList<StreamRequest>()
        override fun describe(request: StreamRequest): Described {
            requests += request
            return answer
        }
    }

    private fun req(
        method: String,
        url: String = "rtsp://panel:8554/live",
        cseq: Int? = 1,
        headers: Map<String, String> = emptyMap(),
    ): RtspRequest {
        val all = HashMap<String, String>()
        cseq?.let { all["cseq"] = it.toString() }
        headers.forEach { (k, v) -> all[k.lowercase()] = v }
        return RtspRequest(method, url, all)
    }

    private fun RtspResponse.header(name: String): String? = headers.firstOrNull { it.first == name }?.second

    private val tcp = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")

    @Test fun audioAndVideoSetupKeepIndependentChannelsAndRejectOverlap() {
        val dual = sdp + "m=audio 0 RTP/AVP 97\r\na=control:trackID=1\r\n"
        val session = RtspSession("77", FakeDescriber(Described.Ready(dual)))
        assertEquals(200, session.handle(req("SETUP", url = "rtsp://panel:8554/live/trackID=0", headers = tcp)).response.status)
        assertEquals(461, session.handle(req("SETUP", url = "rtsp://panel:8554/live/trackID=1", headers = tcp + ("Session" to "77"))).response.status)
        assertEquals(200, session.handle(req("SETUP", url = "rtsp://panel:8554/live/trackID=1", headers = mapOf("Transport" to "RTP/AVP/TCP;interleaved=2-3", "Session" to "77"))).response.status)
        assertEquals("audio SETUP must preserve the video channel", 0, session.rtpChannel)
    }

    @Test fun anAudioTrackAloneCannotStartRoomCapture() {
        val dual = sdp + "m=audio 0 RTP/AVP 97\r\na=control:trackID=1\r\n"
        val session = RtspSession("77", FakeDescriber(Described.Ready(dual)))
        assertEquals(200, session.handle(req("SETUP", url = "rtsp://panel:8554/live/trackID=1", headers = tcp)).response.status)
        assertEquals(455, session.handle(req("PLAY", headers = mapOf("Session" to "77"))).response.status)
    }

    @Test fun theRequestParserReadsTheRequestLineAndCaseInsensitiveHeaders() {
        val parsed = requireNotNull(
            RtspRequest.parse("DESCRIBE rtsp://panel:8554/live?res=480p RTSP/1.0\r\nCSeq: 3\r\nAccept: application/sdp\r\nUser-Agent: go2rtc\r\n"),
        )
        assertEquals("DESCRIBE", parsed.method)
        assertEquals(3, parsed.cseq)
        assertEquals("application/sdp", parsed.header("ACCEPT"))
        assertEquals("/live", parsed.path)
        assertEquals("panel:8554", RtspRequest.authorityOf(parsed.url))
        assertNull("not RTSP at all", RtspRequest.parse("GET / HTTP/1.1\r\nHost: x\r\n"))
        assertNull(RtspRequest.parse(""))
        assertEquals("/live/trackID=0", RtspRequest.pathOf("rtsp://panel:8554/live/trackID=0?fps=5"))
        assertEquals("/", RtspRequest.pathOf("rtsp://panel"))
        assertEquals("/x", RtspRequest.pathOf("/x?y=1"))
    }

    @Test fun theHappyPathIsOptionsDescribeSetupPlayTeardown() {
        val describer = FakeDescriber(Described.Ready(sdp))
        val session = RtspSession("77", describer)
        val options = session.handle(req("OPTIONS", url = "*", cseq = 1))
        assertEquals(200, options.response.status)
        assertTrue(requireNotNull(options.response.header("Public")).contains("DESCRIBE"))
        assertEquals("1", options.response.header("CSeq"))

        val describe = session.handle(req("DESCRIBE", url = "rtsp://panel:8554/live?res=480p", cseq = 2, headers = mapOf("Accept" to "application/sdp")))
        assertEquals(200, describe.response.status)
        assertEquals("application/sdp", describe.response.header("Content-Type"))
        assertEquals("rtsp://panel:8554/live/", describe.response.header("Content-Base"))
        assertEquals(sdp, String(requireNotNull(describe.response.body), Charsets.US_ASCII))
        assertEquals(listOf(StreamRequest(resolution = CameraResolution.P480)), describer.requests)
        assertEquals(State.INIT, session.state)

        val setup = session.handle(
            req("SETUP", url = "rtsp://panel:8554/live/trackID=0", cseq = 3, headers = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3")),
        )
        assertEquals(200, setup.response.status)
        assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", setup.response.header("Transport"))
        assertEquals("77;timeout=60", setup.response.header("Session"))
        assertEquals(2, session.rtpChannel)
        assertEquals(State.READY, session.state)

        val play = session.handle(req("PLAY", cseq = 4, headers = mapOf("Session" to "77")), nextSequence = 500, rtpTimestamp = 9_000L)
        assertEquals(200, play.response.status)
        assertTrue(play.startPlaying)
        assertEquals("url=rtsp://panel:8554/live/trackID=0;seq=500;rtptime=9000", play.response.header("RTP-Info"))
        assertEquals(State.PLAYING, session.state)

        val keepalive = session.handle(req("GET_PARAMETER", cseq = 5, headers = mapOf("Session" to "77")))
        assertEquals(200, keepalive.response.status)
        assertFalse(keepalive.close)

        val teardown = session.handle(req("TEARDOWN", cseq = 6, headers = mapOf("Session" to "77")))
        assertEquals(200, teardown.response.status)
        assertTrue(teardown.close)
        assertTrue(teardown.stopPlaying)
        assertEquals(State.READY, session.state)
    }

    @Test fun aUdpTransportIsRefusedWithUnsupportedTransportSoTheClientFallsBackToTcp() {
        val session = RtspSession("77", FakeDescriber(Described.Ready(sdp)))
        val udp = session.handle(req("SETUP", cseq = 1, headers = mapOf("Transport" to "RTP/AVP;unicast;client_port=5000-5001")))
        assertEquals(461, udp.response.status)
        assertEquals(State.INIT, session.state)
        val both = session.handle(
            req("SETUP", cseq = 2, headers = mapOf("Transport" to "RTP/AVP;unicast;client_port=5000-5001,RTP/AVP/TCP;unicast;interleaved=0-1")),
        )
        assertEquals("the TCP alternative in a list is taken", 200, both.response.status)
        assertEquals(0 to 1, RtspSession.interleavedChannels("RTP/AVP/TCP;unicast"))
        assertNull(RtspSession.interleavedChannels("RTP/AVP/UDP;unicast;client_port=1-2"))
        assertEquals(4 to 5, RtspSession.interleavedChannels("RTP/AVP/TCP;interleaved=4"))
        assertEquals(254 to 255, RtspSession.interleavedChannels("RTP/AVP/TCP;interleaved=254-255"))
        assertNull("a channel must fit the one byte the frame header carries", RtspSession.interleavedChannels("RTP/AVP/TCP;interleaved=300-301"))
        assertNull(RtspSession.interleavedChannels("RTP/AVP/TCP;interleaved=255"))
        assertNull(RtspSession.interleavedChannels("RTP/AVP/TCP;interleaved=-1-0"))
        val outOfRange = RtspSession("77", FakeDescriber(Described.Ready(sdp)))
        assertEquals(461, outOfRange.handle(req("SETUP", cseq = 3, headers = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=256-257"))).response.status)
    }

    @Test fun sessionIdsAreTenAsciiDigitsWhateverThePanelsLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            // A locale whose digits are not ASCII: Arabic-Indic numerals via the Unicode nu extension.
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 123456789).forEach { seed ->
                val id = RtspSession.sessionIdFrom(seed)
                assertTrue("$seed -> $id", Regex("^[0-9]{10}$").matches(id))
            }
            assertEquals("0000000000", RtspSession.sessionIdFrom(0))
            assertEquals("2147483647", RtspSession.sessionIdFrom(Int.MAX_VALUE))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun methodsOutOfOrderAreRefusedByStateAndSessionRatherThanActedOn() {
        val describer = FakeDescriber(Described.Ready(sdp))
        val session = RtspSession("77", describer)
        assertEquals(455, session.handle(req("PLAY", cseq = 1)).response.status)
        assertEquals(455, session.handle(req("PAUSE", cseq = 2)).response.status)
        assertTrue("nothing attached the camera", describer.requests.isEmpty())
        session.handle(req("SETUP", cseq = 3, headers = tcp))
        assertEquals(454, session.handle(req("PLAY", cseq = 4, headers = mapOf("Session" to "99"))).response.status)
        assertEquals(State.READY, session.state)
        val play = session.handle(req("PLAY", cseq = 5, headers = mapOf("Session" to "77;timeout=60")))
        assertEquals(200, play.response.status)
        val again = session.handle(req("SETUP", cseq = 6, headers = mapOf("Session" to "77", "Transport" to "RTP/AVP/TCP")))
        assertEquals("a second SETUP while playing is invalid", 455, again.response.status)
        val replay = session.handle(req("PLAY", cseq = 7, headers = mapOf("Session" to "77")))
        assertEquals(200, replay.response.status)
        assertFalse("already playing: no second start", replay.startPlaying)
        val pause = session.handle(req("PAUSE", cseq = 8, headers = mapOf("Session" to "77")))
        assertTrue(pause.stopPlaying)
        assertEquals(State.READY, session.state)
    }

    @Test fun aRefusedCameraIsServiceUnavailableWithTheClassifiedReasonAndTheWrongPathIsNotFound() {
        val session = RtspSession("77", FakeDescriber(Described.Refused(CameraRefusal.DISABLED)))
        val describe = session.handle(req("DESCRIBE", cseq = 1))
        assertEquals(503, describe.response.status)
        assertEquals("camera-disabled", describe.response.header(RtspSession.CAMERA_HEADER))
        val setup = session.handle(req("SETUP", cseq = 2, headers = tcp))
        assertEquals("a client that skips DESCRIBE meets the same gate", 503, setup.response.status)
        assertEquals(State.INIT, session.state)
        val elsewhere = RtspSession("78", FakeDescriber(Described.Ready(sdp)))
        assertEquals(404, elsewhere.handle(req("DESCRIBE", url = "rtsp://panel:8554/other", cseq = 3)).response.status)
        assertEquals(404, elsewhere.handle(req("SETUP", url = "rtsp://panel:8554/livestream", cseq = 4, headers = tcp)).response.status)
        assertEquals(200, elsewhere.handle(req("DESCRIBE", url = "rtsp://panel:8554/live/", cseq = 5)).response.status)
    }

    @Test fun aMissingCseqOrUnknownMethodIsAnsweredWithoutTouchingTheCamera() {
        val describer = FakeDescriber(Described.Ready(sdp))
        val session = RtspSession("77", describer)
        val bad = session.handle(req("DESCRIBE", cseq = null))
        assertEquals(400, bad.response.status)
        assertTrue(bad.close)
        val unknown = session.handle(req("RECORD", cseq = 9))
        assertEquals(405, unknown.response.status)
        assertEquals("9", unknown.response.header("CSeq"))
        assertTrue(describer.requests.isEmpty())
        val wrongType = session.handle(req("DESCRIBE", cseq = 10, headers = mapOf("Accept" to "text/plain")))
        assertEquals(406, wrongType.response.status)
    }

    @Test fun theDescriptionWithoutAnAdmittedMicrophoneHasOneVideoTrackWithTheParameterSets() {
        val lines = sdp.split("\r\n")
        assertEquals(1, lines.count { it.startsWith("m=") })
        assertEquals("m=video 0 RTP/AVP 96", lines.first { it.startsWith("m=") })
        assertTrue("no audio without an admitted microphone", lines.none { it.startsWith("m=audio") })
        assertTrue(lines.contains("a=rtpmap:96 H264/90000"))
        assertTrue(lines.contains("a=fmtp:96 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=Z0LAH9oBQA==,aM4G4g=="))
        assertTrue(lines.contains("a=control:trackID=0"))
        assertTrue(lines.contains("a=framerate:15"))
        assertTrue(lines.contains("c=IN IP4 0.0.0.0"))
        assertTrue(sdp.endsWith("\r\n"))
    }

    @Test fun aResponseEncodesWithCrLfAndAContentLengthOnlyWhenThereIsABody() {
        val bare = String(RtspResponse(200, "OK", listOf("CSeq" to "1")).encode(), Charsets.US_ASCII)
        assertEquals("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n", bare)
        val withBody = String(RtspResponse(200, "OK", listOf("CSeq" to "2"), "v=0\r\n".toByteArray()).encode(), Charsets.US_ASCII)
        assertEquals("RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 5\r\n\r\nv=0\r\n", withBody)
    }
}
