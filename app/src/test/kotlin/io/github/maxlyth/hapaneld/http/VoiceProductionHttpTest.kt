package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory
import io.github.maxlyth.hapaneld.assist.VoiceTestTrigger
import io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceProductionHttpTest {
    @get:Rule val folder = TemporaryFolder()

    private var microphone = true
    private var enabled = true
    private var directoryReads = 0
    private var triggered = 0
    private var changed = 0
    private var engineAccepts = true
    private var pipelines: AssistPipelineDirectory.Result = AssistPipelineDirectory.Result.Available(
        listOf(AssistPipelineDirectory.Pipeline("home", "Home")), "home",
    )
    private var triggerResult: VoiceTestTrigger.Result = VoiceTestTrigger.Result.Accepted

    private fun ApplicationTestBuilder.mount(catalog: WakeWordCatalog? = null) {
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                route("/api/v1") {
                    voiceRoutes(
                        hasMicrophone = { microphone },
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
                    )
                }
            }
        }
    }

    @Test fun `pipelines report the directory result and recheck live microphone admission`() = testApplication {
        mount()
        val available = client.get("/api/v1/voice/pipelines")
        assertEquals(HttpStatusCode.OK, available.status)
        assertEquals("""{"pipelines":[{"id":"home","name":"Home"}],"preferred":"home"}""", available.bodyAsText())
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
        assertEquals("""{"wake_words":[{"id":"okay_nabu","wake_word":"Okay Nabu","imported":false}]}""", initial.bodyAsText())
        val imported = client.post("/api/v1/voice/wake-words") { setBody(importBody()) }
        assertEquals(HttpStatusCode.OK, imported.status)
        assertEquals("""{"id":"porch","wake_word":"Porch"}""", imported.bodyAsText())
        assertEquals(1, changed)
        val listed = client.get("/api/v1/voice/wake-words")
        assertEquals("""{"wake_words":[{"id":"okay_nabu","wake_word":"Okay Nabu","imported":false},{"id":"porch","wake_word":"Porch","imported":true}]}""", listed.bodyAsText())
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

    @Test fun `oversized import is rejected by body admission before catalog mutation`() = testApplication {
        mount(catalog())
        val oversized = client.post("/api/v1/voice/wake-words") { setBody("x".repeat(4 * 1024 * 1024 + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertEquals("""{"error":"too-large"}""", oversized.bodyAsText())
        assertEquals(0, changed)
    }

    private fun catalog() = WakeWordCatalog(
        bundled = object : WakeWordCatalog.BundledModels {
            override fun ids() = listOf("okay_nabu")
            override fun manifest(id: String) = manifest("Okay Nabu", "okay_nabu.tflite")
            override fun model(file: String) = byteArrayOf(1)
        },
        importDir = folder.root,
        accepts = { _, _ -> engineAccepts },
    )

    private fun importBody() = JSONObject()
        .put("name", "porch")
        .put("manifest", manifest("Porch", "porch.tflite"))
        .put("model", "AQID")
        .toString()

    private fun manifest(phrase: String, model: String) = """
        {"type":"micro","wake_word":"$phrase","author":"me","model":"$model","trained_languages":["en"],"version":2,
         "micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}
    """.trimIndent()
}
