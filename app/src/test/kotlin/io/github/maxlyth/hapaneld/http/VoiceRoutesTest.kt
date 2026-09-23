package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.assist.AssistPipelineDirectory
import io.github.maxlyth.hapaneld.assist.VoiceTestTrigger
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/v1/voice/pipelines` and `POST /api/v1/voice/test` both decide their response through a pure
 * function (voicePipelinesResponse / voiceTestRefusal + voiceTestTriggerResponse) so every branch — the
 * 503/202/409 the checkpoint calls for — is exercised here without needing a routed Ktor request or an
 * Android-bound PaneldServer instance.
 */
class VoiceRoutesTest {
    @Test fun `pipelines available reports 200 with the catalogue and preferred id`() {
        val (status, body) = voicePipelinesResponse(
            AssistPipelineDirectory.Result.Available(
                pipelines = listOf(
                    AssistPipelineDirectory.Pipeline("assist_pipeline_1", "Home"),
                    AssistPipelineDirectory.Pipeline("assist_pipeline_2", "Kitchen"),
                ),
                preferred = "assist_pipeline_1",
            ),
        )
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(
            """{"pipelines":[{"id":"assist_pipeline_1","name":"Home"},{"id":"assist_pipeline_2","name":"Kitchen"}],"preferred":"assist_pipeline_1"}""",
            body,
        )
    }

    @Test fun `pipelines not configured reports 503 with the not-configured error`() {
        val (status, body) = voicePipelinesResponse(AssistPipelineDirectory.Result.NotConfigured("no HA connection"))
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertTrue(body.contains("\"error\":\"not-configured\""))
        assertTrue(body.contains("no HA connection"))
    }

    @Test fun `pipelines unavailable reports 503 with the unavailable error`() {
        val (status, body) = voicePipelinesResponse(AssistPipelineDirectory.Result.Unavailable("fetch failed"))
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertTrue(body.contains("\"error\":\"unavailable\""))
    }

    @Test fun `pipelines are refused with no microphone capability before touching the directory`() {
        assertEquals(
            "this panel has no microphone capability",
            voicePipelinesRefusal(hasMicrophone = false),
        )
    }

    @Test fun `pipelines proceed once the microphone capability holds`() {
        assertEquals(null, voicePipelinesRefusal(hasMicrophone = true))
    }

    @Test fun `the stub directory used before the coordinator lane is wired reports not configured`() {
        val (status, _) = voicePipelinesResponse(
            kotlinx.coroutines.runBlocking { AssistPipelineDirectory.NOT_WIRED.list() },
        )
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
    }

    @Test fun `test trigger is refused with no microphone capability before touching the trigger`() {
        assertEquals(
            "this panel has no microphone capability",
            voiceTestRefusal(hasMicrophone = false, voiceEnabled = true),
        )
    }

    @Test fun `test trigger is refused while voice_enabled is off`() {
        assertEquals(
            "voice assistant is disabled",
            voiceTestRefusal(hasMicrophone = true, voiceEnabled = false),
        )
    }

    @Test fun `the microphone check is checked first when both are false`() {
        // Pins the check order: a capability-less panel gets the capability reason even when it also
        // happens to have voice_enabled off, not the (also true) disabled reason.
        assertEquals(
            "this panel has no microphone capability",
            voiceTestRefusal(hasMicrophone = false, voiceEnabled = false),
        )
    }

    @Test fun `test trigger proceeds once capability and enablement both hold`() {
        assertEquals(null, voiceTestRefusal(hasMicrophone = true, voiceEnabled = true))
    }

    @Test fun `accepted trigger reports 202`() {
        val (status, body) = voiceTestTriggerResponse(VoiceTestTrigger.Result.Accepted)
        assertEquals(HttpStatusCode.Accepted, status)
        assertEquals("""{"accepted":true}""", body)
    }

    @Test fun `refused trigger reports 409 with the reason`() {
        val (status, body) = voiceTestTriggerResponse(VoiceTestTrigger.Result.Refused("a run is already in progress"))
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.contains("already in progress"))
    }

    @Test fun `the stub trigger used before the coordinator lane is wired reports 503`() {
        val (status, _) = voiceTestTriggerResponse(VoiceTestTrigger.NOT_WIRED.trigger())
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
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
