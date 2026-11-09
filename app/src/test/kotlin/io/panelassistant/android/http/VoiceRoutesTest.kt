package io.panelassistant.android.http

import io.panelassistant.android.assist.AssistPipelineDirectory
import io.panelassistant.android.assist.LastCommand
import io.panelassistant.android.assist.VoiceDiagnosticsControl
import io.panelassistant.android.assist.VoiceDiagnosticsSnapshot
import io.panelassistant.android.assist.VoiceLevel
import io.panelassistant.android.assist.VoiceTestTrigger
import io.panelassistant.android.assist.WakeTestReading
import io.panelassistant.android.audio.MicrophonePresence
import io.panelassistant.android.audio.MicrophoneStatus
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.CommandOrigin
import io.panelassistant.android.ManageOperations
import io.panelassistant.android.ManageReply
import io.panelassistant.android.ManageTable
import io.panelassistant.android.security.LocalApprovalBroker
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice settings admission through config POST, and the owner's voice diagnostics: the routes the Voice
 * card's level meter, wake-word test and last command read and drive. The other voice routes are pinned by
 * VoiceProductionHttpTest.
 */
class VoiceRoutesTest {
    private var enabled = true
    private var refusal: String? = null
    private val actions = CopyOnWriteArrayList<String>()
    private var triggered = 0
    private var snapshot = VoiceDiagnosticsSnapshot(
        session = "tab-1",
        level = VoiceLevel(rmsDbfs = -41.25f, peakDbfs = -20f, floorDbfs = -62.5f, clipped = 3),
        wakeTest = WakeTestReading("running", "okay_nabu", 0.8123f, 0.97f, 1, 12_000L),
        lastCommand = LastCommand(1_700_000_000_000L, "okay_nabu", 0.981f, "turn on the light", "Turned on the light", "action_done", null, 2_340L),
    )
    private val control = object : VoiceDiagnosticsControl {
        override fun diagnostics() = snapshot
        override fun diagnosticsAction(action: String, sessionId: String): String? {
            actions += "$action/$sessionId"
            return refusal
        }
    }

    private fun ApplicationTestBuilder.mount(table: ManageTable? = null) {
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                table?.let { serveOperations(it) }
                route("/api/v1") {
                    voiceRoutes(
                        hasMicrophone = { true },
                        microphone = { MicrophoneStatus(MicrophonePresence.PROVEN) },
                        voiceEnabled = { enabled },
                        assistPipelines = object : AssistPipelineDirectory {
                            override suspend fun list() = AssistPipelineDirectory.Result.Unavailable("none")
                        },
                        voiceTest = VoiceTestTrigger { triggered++; VoiceTestTrigger.Result.Accepted },
                        wakeWords = null,
                        onWakeWordsChanged = {},
                        authorize = { _, _, _, _ -> true },
                        voiceDiagnostics = control,
                    )
                }
            }
        }
    }

    @Test fun `diagnostics report the level, the wake-word test and the last command`() = testApplication {
        mount()
        val response = client.get("/api/v1/voice/diagnostics")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(
            """{"session":"tab-1","level":{"rms_dbfs":-41.3,"peak_dbfs":-20.0,"floor_dbfs":-62.5,"clipped":3},""" +
                """"wake_test":{"state":"running","model":"okay_nabu","peak":0.81,"cutoff":0.97,"fired":1,"remaining_ms":12000},""" +
                """"last_command":{"at":1700000000000,"wake_word":"okay_nabu","wake_score":0.98,"heard":"turn on the light",""" +
                """"reply":"Turned on the light","outcome":"action_done","code":null,"listen_ms":2340},"route":null}""",
            response.bodyAsText(),
        )
        snapshot = VoiceDiagnosticsSnapshot.EMPTY
        assertEquals(
            """{"session":null,"level":null,"wake_test":{"state":"idle","model":null,"peak":null,"cutoff":null,"fired":0,"remaining_ms":0},"last_command":null,"route":null}""",
            client.get("/api/v1/voice/diagnostics").bodyAsText(),
        )
    }

    @Test fun `a tuning session is the panel's own UI, takes a session, and answers 202 or 409`() = testApplication {
        mount()
        suspend fun submit(action: String, session: String = "tab-1", marked: Boolean = true) = client.submitForm(
            "/api/v1/voice/diagnostics",
            Parameters.build { append("action", action); append("sessionId", session) },
        ) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            if (marked) header("X-Voice-UI", "1")
        }

        assertEquals(HttpStatusCode.Forbidden, submit("level", marked = false).status)
        assertEquals(HttpStatusCode.BadRequest, submit("calibrate").status)
        assertEquals(HttpStatusCode.BadRequest, submit("level", session = "").status)
        assertTrue("nothing refused reached the coordinator", actions.isEmpty())

        val started = submit("level")
        assertEquals(HttpStatusCode.Accepted, started.status)
        assertEquals("no-store", started.headers[HttpHeaders.CacheControl])
        assertTrue(JSONObject(started.bodyAsText()).has("last_command"))
        assertEquals(HttpStatusCode.Accepted, submit("wake_test").status)
        assertEquals(HttpStatusCode.Accepted, submit("heartbeat").status)

        refusal = "another browser is tuning this panel's microphone"
        val busy = submit("level", session = "tab-2")
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertEquals("""{"error":"another browser is tuning this panel's microphone"}""", busy.bodyAsText())
        refusal = null

        enabled = false
        val off = submit("wake_test")
        assertEquals(HttpStatusCode.Conflict, off.status)
        assertEquals("""{"error":"voice assistant is disabled"}""", off.bodyAsText())
        assertEquals("stopping still reaches the coordinator with voice off", HttpStatusCode.Accepted, submit("stop").status)
        assertEquals(listOf("level/tab-1", "wake_test/tab-1", "heartbeat/tab-1", "level/tab-2", "stop/tab-1"), actions.toList())
    }

    @Test fun `the sidebar relay's page may run tap-to-talk and the microphone tuning`() = testApplication {
        val table = ManageTable()
        mount(table)
        startApplication()
        val relay = CommandOrigin.user("0123456789abcdef0123456789abcdef").withConnection("relay-1")
        fun manage(op: String, args: JSONObject = JSONObject()): ManageReply {
            val answers = LinkedBlockingQueue<ManageReply>()
            ManageOperations(table, { LocalApprovalBroker.instance }).manage("v-$op", op, args, relay) { answers.put(it) }
            return requireNotNull(answers.poll(10, TimeUnit.SECONDS)) { "$op answered" }
        }

        assertEquals("applied", manage("voice.test").outcome)
        assertEquals(1, triggered)
        assertEquals("applied", manage("voice.diagnostics.set", JSONObject().put("action", "level").put("sessionId", "tab-9")).outcome)
        assertEquals(listOf("level/tab-9"), actions.toList())
        assertEquals("applied", manage("voice.diagnostics").outcome)
    }

    @Test fun `voice_wake_words and voice_pipelines round-trip through config POST admission`() {
        val result = normalizeConfigPostParameters(
            Parameters.build {
                append("voice_wake_words", """["hey_jarvis","alexa"]""")
                append("voice_pipelines", """{"hey_jarvis":"assist_pipeline_1"}""")
            },
        )
        assertTrue("expected acceptance, got $result", result is ConfigPostParameters.Ok)
        val ok = result as ConfigPostParameters.Ok
        assertEquals("""["hey_jarvis","alexa"]""", ok.values["voice_wake_words"])
        assertEquals("""{"hey_jarvis":"assist_pipeline_1"}""", ok.values["voice_pipelines"])
    }

    @Test fun `a malformed voice_wake_words value fails admission for the whole request`() {
        val result = normalizeConfigPostParameters(
            Parameters.build {
                append("voice_wake_words", "not json")
                append("panel_id", "alpha")
            },
        )
        assertTrue(result is ConfigPostParameters.Bad)
    }

    @Test fun `voice_enabled is a registered live-apply setting`() {
        assertTrue("voice_enabled" in SettingsRegistry.liveApplyKeys())
    }
}
