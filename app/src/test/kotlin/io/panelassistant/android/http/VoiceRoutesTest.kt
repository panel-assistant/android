package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.ktor.http.Parameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice settings admission through config POST; the voice routes themselves are pinned by VoiceProductionHttpTest. */
class VoiceRoutesTest {
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
