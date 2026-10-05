package io.panelassistant.android.camera

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport over real sockets against a fake camera: the first client takes the lease and the
 * last gives it back, refusals reach the client as 503 with the classified reason, a client that
 * cannot keep up is shed without the encoder's thread blocking, a silent client is dropped by the
 * read timeout, and a stop tears every thread down.
 */
class CameraRtspServerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
    private val sets = ParameterSets(sps, pps)
    private val idr = byteArrayOf(0x65) + ByteArray(40) { it.toByte() }

    private inner class FakeSource(private var refusal: CameraRefusal? = null) : CameraStreamSource {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val keyFrames = AtomicInteger()
        val requests = ArrayList<StreamRequest>()
        /** What the camera does on a sync-frame request: a real owner may deliver one synchronously. */
        var onKeyFrame: (() -> Unit)? = null
        /** The session generation the next grant belongs to; a test moves it to stand for a replacement session. */
        @Volatile var session = 1L
        /** When set, an acquire signals [entered] and waits here before it is answered: a client still being admitted. */
        @Volatile var admitting: CountDownLatch? = null
        val entered = CountDownLatch(1)

        override fun acquireStream(request: StreamRequest): StreamAdmission {
            synchronized(requests) { requests += request }
            admitting?.let {
                entered.countDown()
                check(it.await(5, TimeUnit.SECONDS)) { "the test never released the admission" }
            }
            refusal?.let { return StreamAdmission.Refused(it) }
            acquired.incrementAndGet()
            var open = true
            val lease = AutoCloseable {
                if (open) {
                    open = false
                    released.incrementAndGet()
                }
            }
            return StreamAdmission.Granted(lease, StreamParams(640, 480, 15, 1_000, "fake.encoder", sets), session)
        }

        override fun requestKeyFrame() {
            keyFrames.incrementAndGet()
            onKeyFrame?.invoke()
        }
    }

    private val servers = ArrayList<CameraRtspServer>()

    private fun server(
        source: CameraStreamSource,
        maxClients: Int = 4,
        queuePackets: Int = 512,
        readTimeoutMs: Int = 10_000,
        maxConnections: Int = 8,
        maxBodyBytes: Int = 16 * 1024,
        afterAdoption: () -> Unit = {},
        audio: CameraAudioSource? = null,
    ): CameraRtspServer {
        val s = CameraRtspServer(
            port = 0, source = { source }, maxClients = maxClients, queuePackets = queuePackets,
            readTimeoutMs = readTimeoutMs, maxConnections = maxConnections, maxBodyBytes = maxBodyBytes,
            afterAdoption = afterAdoption, audio = audio,
        )
        servers += s
        s.setListening(true)
        assertNotNull("bound", s.boundPort)
        // The camera publishes its encoder's parameter sets before any client can be granted a stream;
        // without them a DESCRIBE is refused rather than answered with nothing to decode from.
        s.onParameterSets(sets, attempt = 1L)
        return s
    }

    @After fun tearDown() {
        servers.forEach { it.stop() }
    }

    private class Client(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        val input: InputStream = BufferedInputStream(socket.getInputStream())
        private var cseq = 0

        fun send(method: String, url: String, vararg headers: String) {
            val text = buildString {
                append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
                append("CSeq: ").append(++cseq).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(text.toByteArray(Charsets.US_ASCII))
        }

        fun request(method: String, url: String, vararg headers: String): Response {
            send(method, url, *headers)
            return readResponse()
        }

        /** The next byte on the wire without consuming it. */
        fun peek(): Int {
            input.mark(1)
            return input.read().also { input.reset() }
        }

        fun readResponse(): Response {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                check(b >= 0) { "eof" }
                head.write(b)
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
            }
            val lines = head.toString("US-ASCII").split("\r\n").filter { it.isNotEmpty() }
            val status = lines[0].split(' ')[1].toInt()
            val headers = lines.drop(1).associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            val length = headers["Content-Length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                check(n >= 0)
                read += n
            }
            return Response(status, headers, String(body, Charsets.US_ASCII))
        }

        /** The next interleaved frame: channel and RTP packet. */
        fun readFrame(): Pair<Int, ByteArray> {
            val magic = input.read()
            check(magic == '$'.code) { "expected an interleaved frame, got $magic" }
            val channel = input.read()
            val length = (input.read() shl 8) or input.read()
            val packet = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(packet, read, length - read)
                check(n >= 0)
                read += n
            }
            return channel to packet
        }

        fun sendInterleaved(channel: Int, payload: ByteArray) {
            socket.getOutputStream().write(RtspInterleaved.frame(channel, payload))
        }

        /** The next byte, -1 at end of stream, or -2 when the server said nothing within the socket timeout. */
        fun readOrTimeout(): Int = try {
            input.read()
        } catch (_: java.net.SocketTimeoutException) {
            -2
        }

        /** The status of the next response, or null when the connection ended (end of stream or a reset) before one arrived. */
        fun statusOrEnd(): Int? = try {
            readResponse().status
        } catch (_: IllegalStateException) {
            null
        } catch (_: java.net.SocketException) {
            null
        }

        /** True when the server has ended the connection: end of stream or a reset, never a silent timeout. */
        fun ended(): Boolean = try {
            input.read() == -1
        } catch (_: java.net.SocketTimeoutException) {
            false
        } catch (_: java.net.SocketException) {
            true
        }

        fun play(url: String): String {
            request("OPTIONS", url)
            val describe = request("DESCRIBE", url, "Accept: application/sdp")
            check(describe.status == 200) { "describe ${describe.status}: ${describe.headers}" }
            val setup = request("SETUP", "$url/trackID=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
            check(setup.status == 200) { "setup ${setup.status}" }
            val session = requireNotNull(setup.headers["Session"]).substringBefore(';')
            val play = request("PLAY", url, "Session: $session")
            check(play.status == 200) { "play ${play.status}" }
            return session
        }

        override fun close() {
            socket.close()
        }
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test fun theFirstClientTakesTheLeaseReceivesRtpAfterPlayAndItsTeardownGivesTheLeaseBack() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live?res=480p&fps=5"
        Client(server.boundPort!!).use { client ->
            server.onParameterSets(sets, attempt = 1L)
            val session = client.play(url)
            assertEquals(1, source.acquired.get())
            assertEquals(listOf(StreamRequest(resolution = CameraResolution.P480, fps = 5)), source.requests)
            // The 200 PLAY is on the wire before media is enabled; the sync-frame request follows it.
            await("PLAY asks for a sync frame") { source.keyFrames.get() == 1 }
            assertEquals(StreamTransportFacts(port = server.boundPort, clients = 1), server.facts())

            server.onAccessUnit(listOf(idr), keyFrame = true, ptsUs = 1_000_000L, attempt = 1L)
            val first = client.readFrame()
            assertEquals(0, first.first)
            assertArrayEqualsPayload(sps, first.second)
            assertArrayEqualsPayload(pps, client.readFrame().second)
            val idrPacket = client.readFrame().second
            assertArrayEqualsPayload(idr, idrPacket)
            assertTrue("marker on the last packet", idrPacket[1].toInt() and 0x80 != 0)
            assertEquals(90_000L, ((idrPacket[4].toLong() and 0xFF) shl 24) or ((idrPacket[5].toLong() and 0xFF) shl 16) or ((idrPacket[6].toLong() and 0xFF) shl 8) or (idrPacket[7].toLong() and 0xFF))

            val teardown = client.request("TEARDOWN", url, "Session: $session")
            assertEquals(200, teardown.status)
            await("lease release") { source.released.get() == 1 }
            await("client gone") { server.facts().clients == 0 }
        }
    }

    @Test fun anUnadvertisedAudioTrackCannotBeNegotiatedAsAnotherVideoTrack() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            assertEquals(200, client.request("DESCRIBE", url).status)
            assertEquals(404, client.request("SETUP", "$url/trackID=1", "Transport: RTP/AVP/TCP;interleaved=2-3").status)
        }
    }

    private class FakeAudio : CameraAudioSource {
        var permitted = true
        val starts = AtomicInteger()
        val closes = AtomicInteger()
        val outputs = java.util.concurrent.CopyOnWriteArrayList<(ByteArray, Long) -> Unit>()
        override fun available() = permitted
        override fun start(output: (ByteArray, Long) -> Unit): AutoCloseable {
            starts.incrementAndGet()
            outputs += output
            return AutoCloseable { closes.incrementAndGet() }
        }
    }

    private fun Client.playBoth(url: String): String {
        val describe = request("DESCRIBE", url)
        assertEquals(200, describe.status)
        assertTrue(describe.body.contains("m=audio 0 RTP/AVP 97"))
        assertTrue(describe.body.contains("config=1408"))
        val video = request("SETUP", "$url/trackID=0", "Transport: RTP/AVP/TCP;interleaved=0-1")
        val session = requireNotNull(video.headers["Session"]).substringBefore(';')
        assertEquals(200, request("SETUP", "$url/trackID=1", "Transport: RTP/AVP/TCP;interleaved=2-3", "Session: $session").status)
        assertEquals(200, request("PLAY", url, "Session: $session").status)
        return session
    }

    @Test fun audioUsesOneOnDemandEncodeAndNegotiatedChannelsWithVideoOnlyViewersUnaffected() {
        val source = FakeSource()
        val audio = FakeAudio()
        val server = server(source, audio = audio)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { videoOnly ->
            val videoSession = videoOnly.play(url)
            assertEquals("video PLAY never opens the microphone", 0, audio.starts.get())
            Client(server.boundPort!!).use { first ->
                val firstSession = first.playBoth(url)
                await("first audio encode") { audio.starts.get() == 1 }
                Client(server.boundPort!!).use { second ->
                    val secondSession = second.playBoth(url)
                    await("second viewer PLAY") { source.keyFrames.get() == 3 }
                    assertEquals(1, audio.starts.get())
                    val unit = byteArrayOf(1, 2, 3, 4)
                    audio.outputs.single()(unit, 1_000_000L)
                    for (client in listOf(first, second)) {
                        val (channel, packet) = client.readFrame()
                        assertEquals(2, channel)
                        assertEquals(97, packet[1].toInt() and 0x7F)
                        assertEquals(listOf<Byte>(0, 16, 0, 32), packet.copyOfRange(12, 16).toList())
                        assertEquals(unit.toList(), packet.copyOfRange(16, packet.size).toList())
                        assertEquals("audio sender report follows on audio RTCP", 3, client.readFrame().first)
                    }
                    assertEquals(200, first.request("PAUSE", url, "Session: $firstSession").status)
                    assertEquals("the other audio viewer retains capture", 0, audio.closes.get())
                    assertEquals(200, second.request("TEARDOWN", url, "Session: $secondSession").status)
                    await("last audio viewer closes encode") { audio.closes.get() == 1 }
                    assertEquals("the video-only viewer remains controllable", 200, videoOnly.request("GET_PARAMETER", url, "Session: $videoSession").status)
                }
            }
        }
    }

    @Test fun offClosesAudioAndAnOldEncoderCallbackCannotFeedAReplacementSession() {
        val source = FakeSource()
        val audio = FakeAudio()
        val server = server(source, audio = audio)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { old ->
            old.playBoth(url)
            await("old audio started") { audio.starts.get() == 1 }
            server.setListening(false)
            assertTrue(old.ended())
            assertEquals(1, audio.closes.get())
        }
        server.setListening(true)
        Client(server.boundPort!!).use { replacement ->
            val freshUrl = "rtsp://127.0.0.1:${server.boundPort}/live"
            replacement.playBoth(freshUrl)
            await("new audio started") { audio.starts.get() == 2 }
            audio.outputs[0](byteArrayOf(99), 0L)
            audio.outputs[1](byteArrayOf(42), 0L)
            assertEquals(listOf<Byte>(42), replacement.readFrame().second.drop(16))
        }
    }

    @Test fun cameraReopenDropsAudioUntilIndicatedVideoReturnsAndRejectsThePriorAacCallback() {
        val source = FakeSource()
        val audio = FakeAudio()
        val server = server(source, audio = audio)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.playBoth(url)
            await("initial audio") { audio.starts.get() == 1 }
            server.onEncoderStopped(1L)
            assertEquals("mic capture ends before indication can disappear", 1, audio.closes.get())
            server.onParameterSets(sets, 2L)
            await("audio resumes with live video") { audio.starts.get() == 2 }
            server.onEncoderStopped(1L)
            assertEquals("old video stop cannot revoke replacement audio", 1, audio.closes.get())
            audio.outputs[0](byteArrayOf(99), 0L)
            audio.outputs[1](byteArrayOf(42), 0L)
            assertEquals(listOf<Byte>(42), client.readFrame().second.drop(16))
        }
    }

    @Test fun permissionAbsenceAdvertisesOnlyVideoAndTakesNoAudioLease() {
        val source = FakeSource()
        val audio = FakeAudio().apply { permitted = false }
        val server = server(source, audio = audio)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            assertFalse(client.request("DESCRIBE", url).body.contains("m=audio"))
            assertEquals(404, client.request("SETUP", "$url/trackID=1", "Transport: RTP/AVP/TCP;interleaved=2-3").status)
            client.play(url)
            assertEquals(0, audio.starts.get())
        }
    }

    private fun assertArrayEqualsPayload(expected: ByteArray, packet: ByteArray) {
        assertEquals(expected.toList(), packet.copyOfRange(12, packet.size).toList())
    }

    @Test fun aClientThatDisconnectsWithoutTeardownStillGivesTheLeaseBack() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { it.play(url) }
        await("lease release after a bare disconnect") { source.released.get() == 1 }
        assertEquals(0, server.facts().clients)
    }

    @Test fun connectionsBeyondTheBoundAreClosedBeforeTheyOwnAThread() {
        val source = FakeSource()
        val server = server(source, maxConnections = 2)
        // Reader threads are named per client; earlier tests' threads may still be unwinding in this
        // JVM, so only threads that appear after this point are counted.
        fun readers(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("camera-rtsp-client-") }.toSet()
        val before = readers()
        val held = listOf(Client(server.boundPort!!), Client(server.boundPort!!))
        try {
            await("two idle connections are accepted") { (readers() - before).size == 2 }
            Client(server.boundPort!!).use { extra ->
                assertEquals("the third is closed at the acceptor", -1, extra.readOrTimeout())
            }
            assertEquals("and never got a reader thread", 2, (readers() - before).size)
            assertEquals(0, source.acquired.get())
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test fun aRequestBodyBeyondTheBoundIsRefusedWithoutBeingRead() {
        val source = FakeSource()
        val server = server(source, maxBodyBytes = 64)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.send("GET_PARAMETER", url, "Content-Length: 65")
            client.socket.getOutputStream().write(ByteArray(65) { 'x'.code.toByte() })
            // The server closes with that body unread, so the kernel answers with a reset and the peer
            // may lose the 413 before reading it: what holds is that the request is never answered
            // and the connection ends, never that the refusal is observed.
            val status = client.statusOrEnd()
            assertTrue("one byte over the bound is refused or the connection ends, never answered: $status", status == null || status == 413)
            assertTrue("and the connection ends rather than resynchronising past an unread body", client.ended())
        }
        Client(server.boundPort!!).use { client ->
            client.send("GET_PARAMETER", url, "Content-Length: 64")
            client.socket.getOutputStream().write(ByteArray(64) { 'x'.code.toByte() })
            assertEquals("a body at the bound is skipped and the request answered", 200, client.statusOrEnd())
            assertEquals("and the connection continues", 200, client.request("OPTIONS", url).status)
        }
        Client(server.boundPort!!).use { client ->
            // A declared gigabyte with nothing sent after the head: refused from the header alone, before
            // any buffer for it could exist, and with nothing unread the refusal itself is delivered.
            assertEquals(413, client.request("GET_PARAMETER", url, "Content-Length: 1073741824").status)
            assertTrue(client.ended())
        }
        assertEquals("no request took a lease", 0, source.acquired.get())
    }

    @Test fun theSyncFrameRequestedByPlayNeverReachesTheWireAheadOfThePlayResponse() {
        val source = FakeSource()
        val server = server(source)
        // A camera that answers the sync-frame request synchronously, on the requesting thread: the
        // most demanding case for ordering on the one byte stream.
        source.onKeyFrame = { server.onAccessUnit(listOf(idr), keyFrame = true, ptsUs = 0L, attempt = 1L) }
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.request("OPTIONS", url)
            assertEquals(200, client.request("DESCRIBE", url, "Accept: application/sdp").status)
            val setup = client.request("SETUP", "$url/trackID=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1")
            val session = requireNotNull(setup.headers["Session"]).substringBefore(';')
            client.send("PLAY", url, "Session: $session")
            assertEquals("the 200 PLAY is the first thing after PLAY on the wire, never an interleaved frame", 'R'.code, client.peek())
            assertEquals(200, client.readResponse().status)
            val (channel, packet) = client.readFrame()
            assertEquals("the sync frame follows it", 0, channel)
            assertTrue(packet.size > 12)
            assertEquals(1, source.keyFrames.get())
        }
    }

    @Test fun aDescribeWhileTheEncoderIsDownIsRefusedRatherThanAnsweredWithRetainedSets() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            server.onParameterSets(sets, attempt = 1L)
            client.play(url)
            // A reopen: the encoder stops, and nothing is advertised until the new one publishes.
            server.onEncoderStopped(attempt = 1L)
            val stale = client.request("DESCRIBE", url, "Accept: application/sdp")
            assertEquals(503, stale.status)
            assertEquals("camera-starved", stale.headers["X-Camera"])
            val fresh = ParameterSets(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x01), pps)
            server.onParameterSets(fresh, attempt = 2L)
            val again = client.request("DESCRIBE", url, "Accept: application/sdp")
            assertEquals(200, again.status)
            assertTrue("the new encoder's sets, never the old", again.body.contains(fresh.spropParameterSets()))
            assertFalse(again.body.contains(sets.spropParameterSets()))
        }
    }

    @Test fun aRefusedCameraReachesTheClientAsServiceUnavailableWithTheReasonAndTakesNoLease() {
        val source = FakeSource(refusal = CameraRefusal.PERMISSION)
        val server = server(source)
        Client(server.boundPort!!).use { client ->
            val describe = client.request("DESCRIBE", "rtsp://127.0.0.1:${server.boundPort}/live", "Accept: application/sdp")
            assertEquals(503, describe.status)
            assertEquals("camera-permission-needed", describe.headers["X-Camera"])
            assertEquals(0, source.acquired.get())
            assertEquals(0, server.facts().clients)
        }
    }

    @Test fun clientsBeyondTheCapacityAreRefusedAsBusy() {
        val source = FakeSource()
        val server = server(source, maxClients = 1)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { first ->
            first.play(url)
            Client(server.boundPort!!).use { second ->
                val describe = second.request("DESCRIBE", url)
                assertEquals(503, describe.status)
                assertEquals("camera-busy", describe.headers["X-Camera"])
            }
            assertEquals("only the first is attached", 1, source.acquired.get())
        }
    }

    @Test fun theSessionEndingDropsEveryClientAndReleasesEachLeaseExactlyOnce() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { a ->
            Client(server.boundPort!!).use { b ->
                a.play(url)
                b.play(url)
                assertEquals(2, server.facts().clients)
                server.onStreamEnded(through = 1L)
                assertEquals("the client sees end of stream", -1, a.readOrTimeout())
                assertEquals(-1, b.readOrTimeout())
                await("both leases released") { source.released.get() == 2 }
                assertEquals(0, server.facts().clients)
            }
        }
        Thread.sleep(50)
        assertEquals("a client's own close after the drop releases nothing twice", 2, source.released.get())
    }

    @Test fun aClientThatCannotKeepUpIsShedWithoutBlockingTheEncoderThread() {
        val source = FakeSource()
        val server = server(source, queuePackets = 8)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.play(url)
            server.onParameterSets(sets, attempt = 1L)
            val big = byteArrayOf(0x65) + ByteArray(200_000)
            var slowestCallMs = 0L
            var frames = 0
            await("the slow client is dropped", timeoutMs = 20_000) {
                val started = System.nanoTime()
                server.onAccessUnit(listOf(big), keyFrame = true, ptsUs = frames * 66_000L, attempt = 1L)
                frames++
                slowestCallMs = maxOf(slowestCallMs, (System.nanoTime() - started) / 1_000_000)
                server.facts().clients == 0
            }
            assertTrue("the encoder's thread never waited on the socket (slowest call ${slowestCallMs}ms)", slowestCallMs < 500)
            await("lease released") { source.released.get() == 1 }
        }
    }

    @Test fun aSilentClientIsDroppedByTheReadTimeoutSoADeadPeerCannotHoldTheCameraOpen() {
        val source = FakeSource()
        val server = server(source, readTimeoutMs = 300)
        Client(server.boundPort!!).use { client ->
            client.play("rtsp://127.0.0.1:${server.boundPort}/live")
            assertEquals(1, server.facts().clients)
            await("timeout drop", timeoutMs = 3_000) { source.released.get() == 1 }
            assertEquals(0, server.facts().clients)
        }
    }

    @Test fun interleavedReceiverReportsFromTheClientAreConsumedAndRequestsStillAnswered() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            val session = client.play(url)
            client.sendInterleaved(1, ByteArray(24) { 0x7F })
            val keepalive = client.request("GET_PARAMETER", url, "Session: $session")
            assertEquals(200, keepalive.status)
            assertEquals(1, server.facts().clients)
        }
    }

    @Test fun listeningFollowsTheSwitchAndStopTearsEveryThreadDown() {
        val source = FakeSource()
        val server = server(source)
        val port = requireNotNull(server.boundPort)
        Client(port).use { it.play("rtsp://127.0.0.1:$port/live") }
        server.setListening(false)
        assertNull(server.boundPort)
        assertEquals(StreamTransportFacts(port = null, clients = 0), server.facts())
        // The port is free again within a bounded time: a fresh listener can take it. The JDK defers
        // the real close of a ServerSocket until the thread blocked in accept() has unwound, so an
        // immediate probe races that by microseconds (one false red each way was seen); the property
        // is that nothing listens shortly after the switch, so the bind is polled. It binds with
        // SO_REUSEADDR so a TIME_WAIT remnant cannot fail it — Linux still refuses a bind while another
        // socket is LISTENING, which is exactly what is being asserted.
        await("the freed port can be bound again", timeoutMs = 2_000) {
            runCatching {
                java.net.ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress("127.0.0.1", port))
                }.close()
            }.isSuccess
        }
        server.setListening(true)
        assertNotNull("switching back on binds again", server.boundPort)
        server.stop()
        assertNull(server.boundPort)
        server.setListening(true)
        assertNull("a stopped transport never listens again", server.boundPort)
        await("threads gone") {
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("camera-rtsp-") }
        }
        assertFalse(source.released.get() > source.acquired.get())
    }

    // ---- a replacement between a check and its effect ---------------------------------------------------
    //
    // The camera decides whose an effect is under its own lock and then applies it outside, because
    // dropping a client closes that client's lease and re-enters the camera. So each of these effects can
    // reach the transport after a newer session or attempt has taken over, and each one names what it
    // belongs to; the transport applies it only to that.

    private val slice = byteArrayOf(0x41, 0x11, 0x22, 0x33)
    private val laterSlice = byteArrayOf(0x41, 0x44, 0x55, 0x66)
    private val laterSets = ParameterSets(byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x01), pps)

    @Test fun aStreamEndDropsOnlyTheClientsOfTheGenerationThatEnded() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { ended ->
            Client(server.boundPort!!).use { replacement ->
                ended.play(url)
                // The first session ends at generation 2 and a replacement starts at 3 before the first
                // session's ending reaches the transport.
                source.session = 3L
                replacement.play(url)
                // Media is enabled just after the 200 PLAY is queued; the sync-frame request follows it.
                await("both clients are playing") { source.keyFrames.get() == 2 }
                assertEquals(2, server.facts().clients)
                server.onStreamEnded(through = 2L)
                assertTrue("the ended session's client is dropped", ended.ended())
                await("its lease is given back") { source.released.get() == 1 }
                assertEquals("the replacement's client keeps its place", 1, server.facts().clients)
                server.onAccessUnit(listOf(slice), keyFrame = false, ptsUs = 0L, attempt = 1L)
                assertArrayEqualsPayload(slice, replacement.readFrame().second)
                assertEquals("and its lease", 1, source.released.get())
            }
        }
    }

    @Test fun aStreamEndLeavesAClientThatAReplacementSessionIsStillAdmitting() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { ended ->
            ended.play(url)
            val admitting = CountDownLatch(1)
            source.admitting = admitting
            source.session = 3L
            Client(server.boundPort!!).use { replacement ->
                assertEquals(200, replacement.request("OPTIONS", url).status)
                replacement.send("DESCRIBE", url, "Accept: application/sdp")
                assertTrue("the DESCRIBE is inside the camera's admission", source.entered.await(5, TimeUnit.SECONDS))
                server.onStreamEnded(through = 2L)
                assertTrue("the ended session's client is dropped", ended.ended())
                admitting.countDown()
                assertEquals("the replacement's client is admitted, not dropped by the older ending", 200, replacement.statusOrEnd())
                assertEquals(1, server.facts().clients)
            }
        }
    }

    @Test fun aStreamEndStillDropsAClientItsOwnSessionGrantsDuringTheEnd() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        val admitting = CountDownLatch(1)
        source.admitting = admitting
        Client(server.boundPort!!).use { client ->
            client.send("DESCRIBE", url, "Accept: application/sdp")
            assertTrue("the DESCRIBE is inside the camera's admission", source.entered.await(5, TimeUnit.SECONDS))
            // The session this client is joining ends before the camera answers it, and the answer is
            // still a grant from that session: the client is the ended session's, and goes with it.
            server.onStreamEnded(through = 2L)
            admitting.countDown()
            val status = client.statusOrEnd()
            assertTrue("refused or dropped, never described: $status", status == null || status == 503)
            assertTrue("the connection ends as it did before the end was scoped", client.ended())
            await("the lease it was granted is given back") { source.released.get() == 1 }
            assertEquals(0, server.facts().clients)
        }
    }

    @Test fun aStreamEndAfterTheGrantIsAdoptedButBeforeTheDescribeReturnsStillDropsTheClient() {
        val source = FakeSource()
        val adopted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val server = server(source, afterAdoption = {
            adopted.countDown()
            check(resume.await(5, TimeUnit.SECONDS)) { "the test never resumed the DESCRIBE" }
        })
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.send("DESCRIBE", url, "Accept: application/sdp")
            assertTrue("the grant has been adopted", adopted.await(5, TimeUnit.SECONDS))
            // Session 1 ends, and a replacement's encoder publishes, while this DESCRIBE is still between
            // adopting its session-1 grant and returning.
            server.onStreamEnded(through = 2L)
            server.onParameterSets(laterSets, attempt = 2L)
            resume.countDown()
            await("the lease it adopted is given back") { source.released.get() == 1 }
            assertEquals("the ended session's client is not left attached", 0, server.facts().clients)
            assertNull("and never gets as far as PLAY", runCatching { client.play(url) }.getOrNull())
            server.onAccessUnit(listOf(laterSlice), keyFrame = false, ptsUs = 0L, attempt = 2L)
            assertEquals("so no replacement media reaches it", 1, source.acquired.get())
        }
    }

    @Test fun aStreamEndDuringADescribeTheCameraThenRefusesStillDropsTheConnection() {
        val source = FakeSource(refusal = CameraRefusal.STOPPING)
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        val admitting = CountDownLatch(1)
        source.admitting = admitting
        Client(server.boundPort!!).use { client ->
            client.send("DESCRIBE", url, "Accept: application/sdp")
            assertTrue("the DESCRIBE is inside the camera's admission", source.entered.await(5, TimeUnit.SECONDS))
            // The session this DESCRIBE would have joined ends, and the camera then refuses it: the
            // connection belonged to the ending, and goes with it as a stream end always took it.
            server.onStreamEnded(through = 2L)
            admitting.countDown()
            val status = client.statusOrEnd()
            assertTrue("refused or dropped, never described: $status", status == null || status == 503)
            assertTrue("the connection ends", client.ended())
        }
    }

    @Test fun aSupersededAttemptsAccessUnitReachesNoClient() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            client.play(url)
            await("the client is playing") { source.keyFrames.get() == 1 }
            // A reopen: attempt 2's encoder has published, and a unit attempt 1 produced before its
            // teardown arrives after that.
            server.onParameterSets(laterSets, attempt = 2L)
            server.onAccessUnit(listOf(slice), keyFrame = false, ptsUs = 0L, attempt = 1L)
            server.onAccessUnit(listOf(laterSlice), keyFrame = false, ptsUs = 66_000L, attempt = 2L)
            assertArrayEqualsPayload(laterSlice, client.readFrame().second)
        }
    }

    @Test fun aSupersededAttemptsRetractionLeavesTheNewerAttemptsSetsAdvertised() {
        val source = FakeSource()
        val server = server(source)
        val url = "rtsp://127.0.0.1:${server.boundPort}/live"
        Client(server.boundPort!!).use { client ->
            server.onParameterSets(laterSets, attempt = 2L)
            server.onEncoderStopped(attempt = 1L)
            val describe = client.request("DESCRIBE", url, "Accept: application/sdp")
            assertEquals("the newer attempt's sets are still advertised", 200, describe.status)
            assertTrue(describe.body.contains(laterSets.spropParameterSets()))
        }
    }
}
