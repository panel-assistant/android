package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.audio.PcmConsumer
import io.github.maxlyth.hapaneld.audio.PcmFrame
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.HaTransportFault
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The run driver against a scripted socket. Every case is stepped with [runCurrent], so nothing here
 * depends on wall time and a frame is only ever sent because a coroutine actually ran.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistPipelineClientTest {

    @Test fun `matching preferred pipeline speech sends text and plays without attaching audio`() = runTest {
        val catalogSocket = FakeAssistSocket()
        val runSocket = FakeAssistSocket()
        val played = mutableListOf<String>()
        val client = speechClient(catalogSocket, runSocket)
        val run = async {
            client.speakText("  Keep one metre clear  ", "en-GB") { played += it }
        }
        runCurrent()

        catalogSocket.deliver(
            """{"id":1,"type":"result","success":true,"result":{"pipelines":[""" +
                """{"id":"english","name":"English","tts_language":"en-GB","tts_voice":"Jenny"}],""" +
                """"preferred_pipeline":"english"}}""",
        )
        runCurrent()

        val request = org.json.JSONObject(runSocket.sentText.single())
        assertEquals("tts", request.getString("start_stage"))
        assertEquals("tts", request.getString("end_stage"))
        assertEquals("Keep one metre clear", request.getJSONObject("input").getString("text"))
        assertFalse(request.getJSONObject("input").has("sample_rate"))
        assertEquals("english", request.getString("pipeline"))

        runSocket.deliver(event("run-start", """{"runner_data":{}}"""))
        runSocket.deliver(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/setup.mp3"}}"""))
        runSocket.deliver(event("run-end"))
        runCurrent()

        assertTrue(run.isCompleted)
        assertEquals(listOf("https://ha.example/api/tts_proxy/setup.mp3"), played)
        assertEquals("https://ha.example/api/tts_proxy/setup.mp3", run.await().ttsUrl)
        assertEquals(1, catalogSocket.closes)
        assertEquals(1, runSocket.closes)
    }

    @Test fun `blank preferred pipeline speech fails before connecting`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)

        val outcome = harness.client.speakText("  ", "en-GB") { }

        assertEquals(AssistPipelineClient.CODE_INVALID_TEXT, outcome.error?.code)
        assertEquals(0, harness.connects.get())
        assertTrue(socket.sentText.isEmpty())
    }

    @Test fun `speech chooses a matching pipeline when the preferred language differs`() = runTest {
        val catalogSocket = FakeAssistSocket()
        val runSocket = FakeAssistSocket()
        val client = speechClient(catalogSocket, runSocket)
        val run = async { client.speakText("Bitte zurücktreten", "de-DE") { } }
        runCurrent()
        catalogSocket.deliver(
            """{"id":1,"type":"result","success":true,"result":{"pipelines":[""" +
                """{"id":"english","name":"English","tts_language":"en-GB"},""" +
                """{"id":"german","name":"Deutsch","tts_language":"de_DE","tts_voice":"Anna"}],""" +
                """"preferred_pipeline":"english"}}""",
        )
        runCurrent()

        assertEquals("german", org.json.JSONObject(runSocket.sentText.single()).getString("pipeline"))
        runSocket.deliver(event("run-start", """{"runner_data":{}}"""))
        runSocket.deliver(event("tts-end", """{"tts_output":{"url":"/api/tts_proxy/de.mp3"}}"""))
        runSocket.deliver(event("run-end"))
        runCurrent()
        assertNull(run.await().error)
    }

    @Test fun `speech fails closed when pipeline language metadata cannot match`() = runTest {
        val catalogSocket = FakeAssistSocket()
        val unusedRunSocket = FakeAssistSocket()
        val client = speechClient(catalogSocket, unusedRunSocket)
        val run = async { client.speakText("Bitte zurücktreten", "de-DE") { } }
        runCurrent()
        catalogSocket.deliver(
            """{"id":1,"type":"result","success":true,"result":{"pipelines":[""" +
                """{"id":"unknown","name":"No metadata"},{"id":"english","name":"English","tts_language":"en"}],""" +
                """"preferred_pipeline":"unknown"}}""",
        )
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_TTS_LANGUAGE_UNAVAILABLE, run.await().error?.code)
        assertEquals(1, catalogSocket.sentText.size)
        assertTrue(unusedRunSocket.sentText.isEmpty())
    }

    @Test fun `a rejected credential fails without opening a socket`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, session = HaApiSession("https://ha.example", null, rejected = true))
        val run = harness.list(this)
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_AUTH_REJECTED, run.await().failure?.code)
        assertEquals(0, harness.connects.get())
        assertEquals(listOf(false), harness.forces)
    }

    @Test fun `an untried credential and a missing one fail apart from each other`() = runTest {
        val untried = harness(
            FakeAssistSocket(),
            session = HaApiSession("https://ha.example", null, notAttempted = true),
        )
        val absent = harness(FakeAssistSocket(), session = HaApiSession("https://ha.example", null))

        val untriedRun = untried.list(this)
        val absentRun = absent.list(this)
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_CREDENTIALS_UNAVAILABLE, untriedRun.await().failure?.code)
        assertEquals(AssistPipelineClient.CODE_NOT_CONFIGURED, absentRun.await().failure?.code)
        assertEquals(0, untried.connects.get())
        assertEquals(0, absent.connects.get())
    }

    @Test fun `a refused token is retried once with a forced refresh`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket, refuseFirstConnections = 1)
        val run = harness.list(this)
        runCurrent()

        assertEquals(2, harness.connects.get())
        assertEquals(listOf(false, true), harness.forces)

        socket.deliver("""{"id":1,"type":"result","success":true,"result":{"pipelines":[],"preferred_pipeline":null}}""")
        runCurrent()
        assertNull(run.await().failure)
    }

    @Test fun `a token refused twice stops instead of storming`() = runTest {
        val harness = harness(FakeAssistSocket(), refuseFirstConnections = 2)
        val run = harness.list(this)
        runCurrent()

        assertEquals(AssistPipelineClient.CODE_AUTH_REJECTED, run.await().failure?.code)
        assertEquals(2, harness.connects.get())
        assertEquals(listOf(false, true), harness.forces)
    }

    @Test fun `listing pipelines returns the catalog and closes its socket`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val listing: Deferred<AssistCatalogResult> = async { harness.client.listPipelines() }
        runCurrent()

        assertEquals(
            AssistPipelineJson.LIST_TYPE,
            org.json.JSONObject(socket.sentText.single()).getString("type"),
        )
        socket.deliver(
            """{"id":1,"type":"result","success":true,"result":{"pipelines":[{"id":"01","name":"Home Assistant"}],""" +
                """"preferred_pipeline":"01"}}""",
        )
        runCurrent()

        val catalog = (listing.await() as AssistCatalogResult.Catalog).catalog
        assertEquals(listOf(AssistPipeline("01", "Home Assistant")), catalog.pipelines)
        assertEquals("01", catalog.preferredId)
        assertEquals(1, socket.closes)
    }

    @Test fun `cancelling a listing still closes its socket`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val listing = async { harness.client.listPipelines() }
        runCurrent()

        listing.cancel()
        runCurrent()

        assertTrue(listing.isCancelled)
        assertEquals(1, socket.closes)
    }

    @Test fun `a refused listing reports the server's own reason`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(socket)
        val listing = async { harness.client.listPipelines() }
        runCurrent()
        socket.deliver("""{"id":1,"type":"result","success":false,"error":{"code":"unauthorized","message":"no"}}""")
        runCurrent()

        assertEquals(AssistError("unauthorized", "no"), (listing.await() as AssistCatalogResult.Failed).error)
    }

    @Test fun `an unreachable Home Assistant is not a missing credential`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(
            socket,
            session = HaApiSession(
                "https://ha.example",
                null,
                transientDetail = "failed to connect: unroutable-endpoint-detail",
                transientEvidence = HaTransportEvidence(HaTransportFault.DNS),
            ),
        )
        val run = harness.list(this)
        runCurrent()

        val error = run.await().failure
        // Telling the owner to sign in again would be a lie about a panel whose network is broken.
        assertEquals(AssistPipelineClient.CODE_HA_UNREACHABLE, error?.code)
        assertTrue("the classified fault is what a diagnostic surface can paste", error?.message?.contains("dns") == true)
        // Raw platform text can embed the configured host, so it is never the thing reported.
        assertFalse(error?.message?.contains("unroutable-endpoint-detail") == true)
        assertEquals(0, harness.connects.get())
    }

    @Test fun `an unclassified transport failure still reports unreachable`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(
            socket,
            session = HaApiSession("https://ha.example", null, transientDetail = "something went wrong"),
        )
        val run = harness.list(this)
        runCurrent()

        val error = run.await().failure
        // "We did not classify it" must degrade to unknown, never to a healthy-looking absence.
        assertEquals(AssistPipelineClient.CODE_HA_UNREACHABLE, error?.code)
        assertTrue(error?.message?.contains("unknown") == true)
    }

    @Test fun `a refresh that fails in transport is not a rejection`() = runTest {
        val socket = FakeAssistSocket()
        val harness = harness(
            socket,
            refuseFirstConnections = 1,
            refreshSession = HaApiSession(
                "https://ha.example",
                null,
                transientEvidence = HaTransportEvidence(HaTransportFault.TIMEOUT),
            ),
        )
        val run = harness.list(this)
        runCurrent()

        val error = run.await().failure
        // The token was refused once and the refresh never reached the server: that is a network
        // fault, not a credential the owner has to replace.
        assertEquals(AssistPipelineClient.CODE_HA_UNREACHABLE, error?.code)
        assertTrue(error?.message?.contains("timeout") == true)
        assertEquals(listOf(false, true), harness.forces)
        assertEquals(1, harness.connects.get())
    }

    private fun TestScope.harness(
        socket: FakeAssistSocket,
        session: HaApiSession = HaApiSession("https://ha.example", "token"),
        refuseFirstConnections: Int = 0,
        refreshSession: HaApiSession? = null,
    ): Harness = Harness(socket, session, refuseFirstConnections, refreshSession, testScheduler)

    private fun TestScope.speechClient(vararg sockets: FakeAssistSocket): AssistPipelineClient {
        val next = AtomicInteger()
        return AssistPipelineClient(
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token") },
            transport = AssistTransport { _, _ -> sockets[next.getAndIncrement()] },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    private class Harness(
        private val socket: FakeAssistSocket,
        private val session: HaApiSession,
        private val refuseFirstConnections: Int,
        private val refreshSession: HaApiSession?,
        scheduler: TestCoroutineScheduler,
    ) {
        val forces = mutableListOf<Boolean>()
        val connects = AtomicInteger()

        val client = AssistPipelineClient(
            auth = HaApiSessionProvider { force ->
                forces += force
                if (force) refreshSession ?: session else session
            },
            transport = AssistTransport { _, _ ->
                if (connects.incrementAndGet() <= refuseFirstConnections) {
                    throw HaAuthenticationException("Home Assistant rejected the access token")
                }
                socket
            },
            dispatcher = StandardTestDispatcher(scheduler),
        )

        fun list(scope: TestScope): Deferred<AssistCatalogResult> = scope.async { client.listPipelines() }
    }

    /** The error of a failed listing, or null for a catalogue. */
    private val AssistCatalogResult.failure: AssistError?
        get() = (this as? AssistCatalogResult.Failed)?.error

    private class FakeAssistSocket : AssistSocket {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sentText = mutableListOf<String>()
        var failSends = false
        var closes = 0
            private set

        fun deliver(raw: String) {
            inbound.trySend(raw)
        }

        fun endOfStream() {
            inbound.close()
        }

        override suspend fun receiveText(): String? = inbound.receiveCatching().getOrNull()

        override suspend fun sendText(text: String) {
            if (failSends) throw java.io.IOException("broken pipe")
            sentText += text
        }

        override suspend fun close() {
            // A real socket close suspends. Without a suspension point here a cancelled run would
            // appear to close cleanly whether or not teardown is protected, and the cancellation
            // contract would be untestable.
            kotlinx.coroutines.yield()
            closes++
            inbound.close()
        }
    }

    private companion object {
        fun event(name: String, data: String = "{}") =
            """{"id":1,"type":"event","event":{"type":"$name","data":$data,"timestamp":1.0}}"""
    }
}
