package io.panelassistant.android.http

import io.panelassistant.android.assist.AssistPipelineDirectory
import io.panelassistant.android.assist.VoiceTestTrigger
import io.panelassistant.android.assist.wakeword.WakeWordCatalog
import io.panelassistant.android.audio.MicrophoneCheck
import io.panelassistant.android.audio.MicrophonePresence
import io.panelassistant.android.audio.MicrophoneStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.panelassistant.android.Config
import io.panelassistant.android.security.LocalApprovalBroker
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceProductionHttpTest {
    @get:Rule val folder = TemporaryFolder()

    private var microphone = true
    private var microphoneStatus = MicrophoneStatus(MicrophonePresence.PROVEN)
    private var enabled = true
    private var directoryReads = 0
    private var triggered = 0
    private var changed = 0
    private var engineAccepts = true
    private var pipelines: AssistPipelineDirectory.Result = AssistPipelineDirectory.Result.Available(
        listOf(AssistPipelineDirectory.Pipeline("home", "Home"), AssistPipelineDirectory.Pipeline("kitchen", "Kitchen")), "home",
    )
    private var triggerResult: VoiceTestTrigger.Result = VoiceTestTrigger.Result.Accepted

    private fun ApplicationTestBuilder.mount(catalog: WakeWordCatalog? = null) {
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                route("/api/v1") {
                    voiceRoutes(
                        hasMicrophone = { microphone },
                        microphone = { microphoneStatus },
                        voiceEnabled = { enabled },
                        assistPipelines = object : AssistPipelineDirectory {
                            override suspend fun list(): AssistPipelineDirectory.Result {
                                directoryReads++
                                return pipelines
                            }
                        },
                        voiceTest = VoiceTestTrigger { triggered++; triggerResult },
                        wakeWords = catalog,
                        onWakeWordsChanged = { changed++ },
                        authorize = { _, _, _, _ -> true },
                    )
                }
            }
        }
    }

    @Test fun `the microphone route says what the panel has and what its capture check found`() = testApplication {
        mount()
        val cases = listOf(
            MicrophoneStatus(MicrophonePresence.PROVEN) to
                """{"presence":"proven","check":"not_run","detail":null,"muted":false}""",
            MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.PASSED) to
                """{"presence":"unproven","check":"passed","detail":null,"muted":false}""",
            MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.SILENT) to
                """{"presence":"unproven","check":"silent","detail":null,"muted":false}""",
            MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.NO_AUDIO, "AudioRecord init failed") to
                """{"presence":"unproven","check":"no_audio","detail":"AudioRecord init failed","muted":false}""",
            MicrophoneStatus(MicrophonePresence.ABSENT) to
                """{"presence":"absent","check":"not_run","detail":null,"muted":false}""",
            MicrophoneStatus(MicrophonePresence.UNPROVEN, muted = true) to
                """{"presence":"unproven","check":"not_run","detail":null,"muted":true}""",
        )
        cases.forEach { (status, body) ->
            microphoneStatus = status
            val response = client.get("/api/v1/voice/microphone")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(body, response.bodyAsText())
        }
    }

    @Test fun `pipelines report the directory result and recheck live microphone admission`() = testApplication {
        mount()
        val available = client.get("/api/v1/voice/pipelines")
        assertEquals(HttpStatusCode.OK, available.status)
        assertEquals("""{"pipelines":[{"id":"home","name":"Home"},{"id":"kitchen","name":"Kitchen"}],"preferred":"home"}""", available.bodyAsText())
        microphone = false
        val refused = client.get("/api/v1/voice/pipelines")
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertEquals("""{"error":"unavailable","reason":"this panel has no microphone capability"}""", refused.bodyAsText())
        assertEquals(1, directoryReads)
        microphone = true
        pipelines = AssistPipelineDirectory.Result.NotConfigured("not configured")
        val unconfigured = client.get("/api/v1/voice/pipelines")
        assertEquals(HttpStatusCode.ServiceUnavailable, unconfigured.status)
        assertEquals("""{"error":"not-configured","reason":"not configured"}""", unconfigured.bodyAsText())
        pipelines = AssistPipelineDirectory.Result.Unavailable("offline")
        val unavailable = client.get("/api/v1/voice/pipelines")
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("""{"error":"unavailable","reason":"offline"}""", unavailable.bodyAsText())
    }

    @Test fun `test admission refuses before triggering and successful admission keeps result status`() = testApplication {
        mount()
        microphone = false
        enabled = false
        val noMicrophone = client.post("/api/v1/voice/test")
        assertEquals(HttpStatusCode.Conflict, noMicrophone.status)
        assertEquals("""{"reason":"this panel has no microphone capability"}""", noMicrophone.bodyAsText())
        microphone = true
        val disabled = client.post("/api/v1/voice/test")
        assertEquals(HttpStatusCode.Conflict, disabled.status)
        assertEquals("""{"reason":"voice assistant is disabled"}""", disabled.bodyAsText())
        assertEquals(0, triggered)
        enabled = true
        val accepted = client.post("/api/v1/voice/test")
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals("""{"accepted":true}""", accepted.bodyAsText())
        triggerResult = VoiceTestTrigger.Result.Refused("busy")
        val busy = client.post("/api/v1/voice/test")
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertEquals("""{"reason":"busy"}""", busy.bodyAsText())
        triggerResult = VoiceTestTrigger.Result.Unavailable("not wired")
        val unavailable = client.post("/api/v1/voice/test")
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("""{"reason":"not wired"}""", unavailable.bodyAsText())
        assertEquals(3, triggered)
    }

    @Test fun `root rejects cross origin writes and untrusted hosts without running voice work`() = testApplication {
        mount(catalog())
        val crossOrigin = client.post("/api/v1/voice/test") { header(HttpHeaders.Origin, "http://elsewhere.example") }
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
        assertEquals("cross-origin refused\n", crossOrigin.bodyAsText())
        val import = client.post("/api/v1/voice/wake-words") {
            header(HttpHeaders.Origin, "http://elsewhere.example")
            setBody(importBody())
        }
        assertEquals(HttpStatusCode.Forbidden, import.status)
        val host = client.get("/api/v1/voice/pipelines") { header(HttpHeaders.Host, "elsewhere.example") }
        assertEquals(HttpStatusCode.Forbidden, host.status)
        assertEquals("host not allowed\n", host.bodyAsText())
        assertEquals("nosniff", host.headers["X-Content-Type-Options"])
        assertEquals("DENY", crossOrigin.headers["X-Frame-Options"])
        assertEquals(0, triggered)
        assertEquals(0, changed)
        assertEquals(0, directoryReads)
    }

    @Test fun `wake word routes refuse a missing catalog before receiving imports`() = testApplication {
        mount()
        val list = client.get("/api/v1/voice/wake-words")
        val import = client.post("/api/v1/voice/wake-words") { setBody("not json") }
        assertEquals(HttpStatusCode.ServiceUnavailable, list.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, import.status)
        assertEquals("""{"error":"unavailable"}""", import.bodyAsText())
        assertEquals(0, changed)
    }

    @Test fun `wake word routes recheck microphone capability before listing or importing`() = testApplication {
        mount(catalog())
        microphone = false
        val list = client.get("/api/v1/voice/wake-words")
        val import = client.post("/api/v1/voice/wake-words") { setBody(importBody()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, list.status)
        assertEquals("""{"error":"unavailable"}""", list.bodyAsText())
        assertEquals(HttpStatusCode.ServiceUnavailable, import.status)
        assertEquals(0, changed)
    }

    @Test fun `valid import is visible in the next list and notifies after installation`() = testApplication {
        mount(catalog())
        val initial = client.get("/api/v1/voice/wake-words")
        assertEquals(HttpStatusCode.OK, initial.status)
        assertEquals("""{"wake_words":[{"id":"okay_nabu","wake_word":"Okay Nabu","imported":false}],"downloads":[]}""", initial.bodyAsText())
        val imported = client.post("/api/v1/voice/wake-words") { setBody(importBody()) }
        assertEquals(HttpStatusCode.OK, imported.status)
        assertEquals("""{"id":"porch","wake_word":"Porch"}""", imported.bodyAsText())
        assertEquals(1, changed)
        val listed = client.get("/api/v1/voice/wake-words")
        assertEquals("""{"wake_words":[{"id":"okay_nabu","wake_word":"Okay Nabu","imported":false},{"id":"porch","wake_word":"Porch","imported":true}],"downloads":[]}""", listed.bodyAsText())
    }

    @Test fun `malformed and engine refused imports never announce a change`() = testApplication {
        mount(catalog())
        val malformed = client.post("/api/v1/voice/wake-words") { setBody("not json") }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("""{"error":"expected name, manifest and a base64 model"}""", malformed.bodyAsText())
        engineAccepts = false
        val rejected = client.post("/api/v1/voice/wake-words") { setBody(importBody()) }
        assertEquals(HttpStatusCode.UnprocessableEntity, rejected.status)
        assertEquals("""{"error":"the model was not accepted by the wake-word engine"}""", rejected.bodyAsText())
        assertEquals(0, changed)
    }

    @Test fun `a listed model is offered with its author and licence, then downloaded, announced and shown as held`() = testApplication {
        mount(catalog(downloads = true))
        val offered = JSONObject(client.get("/api/v1/voice/wake-words").bodyAsText()).getJSONArray("downloads").getJSONObject(0)
        assertEquals("hey_donut", offered.getString("id"))
        assertEquals("Hey Donut", offered.getString("wake_word"))
        assertEquals("nyc-esq", offered.getString("author"))
        assertEquals("CC0-1.0", offered.getString("license"))
        assertEquals(LICENSE_URL, offered.getString("license_url"))
        assertEquals(3, offered.getInt("bytes"))
        assertEquals(false, offered.getBoolean("installed"))

        val downloaded = client.post("/api/v1/voice/wake-words") { setBody("""{"download":"hey_donut"}""") }

        assertEquals(HttpStatusCode.OK, downloaded.status)
        assertEquals("""{"id":"hey_donut","wake_word":"Hey Donut"}""", downloaded.bodyAsText())
        assertEquals(1, changed)
        val listed = JSONObject(client.get("/api/v1/voice/wake-words").bodyAsText())
        assertEquals("hey_donut", listed.getJSONArray("wake_words").getJSONObject(1).getString("id"))
        assertEquals(true, listed.getJSONArray("downloads").getJSONObject(0).getBoolean("installed"))
    }

    @Test fun `a download that fails its pinned digest is refused and never announces a change`() = testApplication {
        mount(catalog(downloads = true))
        served[MODEL_URL] = byteArrayOf(9, 9, 9)

        val refused = client.post("/api/v1/voice/wake-words") { setBody("""{"download":"hey_donut"}""") }

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("""{"error":"the download does not match its pinned SHA-256"}""", refused.bodyAsText())
        assertEquals(0, changed)
        assertEquals(1, JSONObject(client.get("/api/v1/voice/wake-words").bodyAsText()).getJSONArray("wake_words").length())
    }

    @Test fun `a download that does not complete answers bad gateway and never announces a change`() = testApplication {
        mount(catalog(downloads = true))
        served.remove(MODEL_URL)

        val failed = client.post("/api/v1/voice/wake-words") { setBody("""{"download":"hey_donut"}""") }

        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertEquals("""{"error":"the download did not complete"}""", failed.bodyAsText())
        assertEquals(0, changed)
    }

    @Test fun `oversized import is rejected by body admission before catalog mutation`() = testApplication {
        mount(catalog())
        val oversized = client.post("/api/v1/voice/wake-words") { setBody("x".repeat(4 * 1024 * 1024 + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("""{"error":"too-large"}""", oversized.bodyAsText())
        assertEquals(0, changed)
    }

    @Test fun `hardened LAN model imports wait for exact one shot approval before native acceptance`() {
        val catalog = catalog(downloads = true)
        PaneldServerHttpFixture(wakeWords = catalog).use { fixture ->
            fixture.useVoice(hasMicrophone = true)
            fixture.config.setSecurityMode(Config.SecurityMode.HARDENED)
            try {
                LocalApprovalBroker.instance.clear()
                testApplication {
                    application {
                        intercept(ApplicationCallPipeline.Setup) {
                            context.mutableOriginConnectionPoint.remoteAddress = "192.168.50.20"
                        }
                        fixture.mount(this)
                    }
                    suspend fun upload(body: String = importBody()) = client.post("/api/v1/voice/wake-words") { setBody(body) }
                    val pending = upload()
                    assertEquals(HttpStatusCode.Accepted, pending.status)
                    val challenge = JSONObject(pending.bodyAsText())
                    assertEquals("approval-required", challenge.getString("error"))
                    assertEquals(listOf("okay_nabu"), catalog.available().map { it.id })
                    assertTrue(LocalApprovalBroker.instance.approve(challenge.getString("approval_id")))
                    val differentManifest = JSONObject(importBody()).put("manifest", manifest("Changed", "porch.tflite")).toString()
                    assertEquals(HttpStatusCode.Accepted, upload(differentManifest).status)
                    assertEquals(listOf("okay_nabu"), catalog.available().map { it.id })
                    assertEquals(HttpStatusCode.OK, upload().status)
                    assertEquals(listOf("okay_nabu", "porch"), catalog.available().map { it.id })
                    assertEquals(HttpStatusCode.Accepted, upload().status)
                    // Digest-pinned catalogue downloads keep the established exemption.
                    val download = client.post("/api/v1/voice/wake-words") { setBody("""{"download":"hey_donut"}""") }
                    assertEquals(HttpStatusCode.OK, download.status)
                }
            } finally { LocalApprovalBroker.instance.clear() }
        }
    }

    @Test fun `administrator sidebar import keeps its hardened mode exemption`() {
        val catalog = catalog()
        PaneldServerHttpFixture(wakeWords = catalog).use { fixture ->
            fixture.useVoice(hasMicrophone = true)
            fixture.config.setSecurityMode(Config.SecurityMode.HARDENED)
            try {
                LocalApprovalBroker.instance.clear()
                testApplication {
                    application { fixture.mount(this) }
                    startApplication()
                    val reply = kotlinx.coroutines.CompletableDeferred<io.panelassistant.android.ManageReply>()
                    fixture.door.manage("admin-import", "voice.wake_words.set", JSONObject(importBody()),
                        io.panelassistant.android.CommandOrigin.user("0123456789abcdef0123456789abcdef"),
                        null, { error("administrator import must not ask for approval") }, { reply.complete(it) })
                    val result = kotlinx.coroutines.withTimeout(5000) { reply.await() }
                    assertEquals("applied", result.outcome)
                    assertEquals(listOf("okay_nabu", "porch"), catalog.available().map { it.id })
                }
            } finally { LocalApprovalBroker.instance.clear() }
        }
    }

    private val donutManifest = manifest("Hey Donut", "hey_donut.tflite", author = "nyc-esq").toByteArray()
    private val donutModel = byteArrayOf(1, 2, 3)
    private val served = mutableMapOf(MANIFEST_URL to donutManifest, MODEL_URL to donutModel)

    private fun catalog(downloads: Boolean = false) = WakeWordCatalog(
        bundled = object : WakeWordCatalog.BundledModels {
            override fun ids() = listOf("okay_nabu")
            override fun manifest(id: String) = manifest("Okay Nabu", "okay_nabu.tflite")
            override fun model(file: String) = byteArrayOf(1)
            override fun downloads() = if (!downloads) "{}" else JSONObject().put(
                "models",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("id", "hey_donut").put("wake_word", "Hey Donut").put("author", "nyc-esq")
                        .put("license", "CC0-1.0").put("license_url", LICENSE_URL)
                        .put("source", "https://github.com/nyc-esq/hey-donut")
                        .put("manifest_url", MANIFEST_URL)
                        .put("manifest_sha256", WakeWordCatalog.sha256(donutManifest))
                        .put("model_sha256", WakeWordCatalog.sha256(donutModel))
                        .put("model_bytes", donutModel.size),
                ),
            ).toString()
        },
        importDir = folder.root,
        accepts = { _, _ -> engineAccepts },
        fetch = { url, dest, maxBytes ->
            val bytes = served[url]
            if (bytes == null || bytes.size > maxBytes) false else { dest.writeBytes(bytes); true }
        },
    )

    private fun importBody() = JSONObject()
        .put("name", "porch")
        .put("manifest", manifest("Porch", "porch.tflite"))
        .put("model", "AQID")
        .toString()

    private companion object {
        const val PINNED = "https://raw.githubusercontent.com/nyc-esq/hey-donut/16b6bb104d5b7ce04b2f882c96dfaad60b292d25"
        const val MANIFEST_URL = "$PINNED/model/hey_donut.json"
        const val MODEL_URL = "$PINNED/model/hey_donut.tflite"
        const val LICENSE_URL = "https://github.com/nyc-esq/hey-donut/blob/16b6bb104d5b7ce04b2f882c96dfaad60b292d25/LICENSE"
    }

    private fun manifest(phrase: String, model: String, author: String = "me") = """
        {"type":"micro","wake_word":"$phrase","author":"$author","model":"$model","trained_languages":["en"],"version":2,
         "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}
    """.trimIndent()
}
