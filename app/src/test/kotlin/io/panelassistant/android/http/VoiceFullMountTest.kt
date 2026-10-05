package io.panelassistant.android.http

import io.panelassistant.android.assist.AssistPipelineDirectory
import io.panelassistant.android.assist.VoiceTestTrigger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFullMountTest {
    @Test fun `full mount reads current microphone capability before calling the pipeline directory`() {
        PaneldServerHttpFixture().use { fixture ->
            var directoryReads = 0
            val directory = object : AssistPipelineDirectory {
                override suspend fun list(): AssistPipelineDirectory.Result {
                    directoryReads++
                    return AssistPipelineDirectory.Result.Available(
                        listOf(AssistPipelineDirectory.Pipeline("home", "Home")), "home",
                    )
                }
            }
            fixture.useVoice(hasMicrophone = false, assistPipelines = directory)
            testApplication {
                application { fixture.mount(this) }
                val absent = client.get("/api/v1/voice/pipelines")
                assertEquals(HttpStatusCode.ServiceUnavailable, absent.status)
                assertEquals("""{"error":"unavailable","reason":"this panel has no microphone capability"}""", absent.bodyAsText())
                assertEquals(0, directoryReads)

                fixture.useVoice(hasMicrophone = true, assistPipelines = directory)
                val available = client.get("/api/v1/voice/pipelines")
                assertEquals(HttpStatusCode.OK, available.status)
                assertEquals("""{"pipelines":[{"id":"home","name":"Home"}],"preferred":"home"}""", available.bodyAsText())
                assertEquals(1, directoryReads)

                fixture.useVoice(hasMicrophone = false, assistPipelines = directory)
                assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/voice/pipelines").status)
                assertEquals(1, directoryReads)
            }
        }
    }

    @Test fun `full mount checks current enablement and root admission before triggering voice`() {
        PaneldServerHttpFixture().use { fixture ->
            var triggered = 0
            val trigger = VoiceTestTrigger { triggered++; VoiceTestTrigger.Result.Accepted }
            fixture.useVoice(hasMicrophone = true, voiceTest = trigger)
            testApplication {
                application { fixture.mount(this) }
                val disabled = client.post("/api/v1/voice/test")
                assertEquals(HttpStatusCode.Conflict, disabled.status)
                assertEquals("""{"reason":"voice assistant is disabled"}""", disabled.bodyAsText())
                assertEquals(0, triggered)

                fixture.useVoice(hasMicrophone = true, enabled = true, voiceTest = trigger)
                val accepted = client.post("/api/v1/voice/test")
                assertEquals(HttpStatusCode.Accepted, accepted.status)
                assertEquals("""{"accepted":true}""", accepted.bodyAsText())
                assertEquals(1, triggered)

                val crossOrigin = client.post("/api/v1/voice/test") {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
                assertEquals("cross-origin refused\n", crossOrigin.bodyAsText())
                assertEquals("nosniff", crossOrigin.headers["X-Content-Type-Options"])
                assertEquals(1, triggered)

                fixture.useVoice(hasMicrophone = true, enabled = false, voiceTest = trigger)
                assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/voice/test").status)
                assertEquals(1, triggered)
            }
        }
    }
}
