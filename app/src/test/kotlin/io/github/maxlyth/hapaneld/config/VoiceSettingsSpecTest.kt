package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray

class VoiceSettingsSpecTest {
    private val voiceEnabled = requireNotNull(SettingsRegistry.spec("voice_enabled"))
    private val wakeWords = requireNotNull(SettingsRegistry.spec("voice_wake_words"))
    private val pipelines = requireNotNull(SettingsRegistry.spec("voice_pipelines"))
    private val audioSource = requireNotNull(SettingsRegistry.spec("voice_audio_source"))
    private val sensitivity = requireNotNull(SettingsRegistry.spec("voice_sensitivity"))

    private val micGain = requireNotNull(SettingsRegistry.spec("voice_mic_gain_db"))

    private val everyMicrophoneGatedVoiceSpec =
        listOf(voiceEnabled, wakeWords, pipelines, audioSource, sensitivity, micGain)

    @Test fun `every voice setting requires the microphone capability and lives in the Voice group`() {
        everyMicrophoneGatedVoiceSpec.forEach { spec ->
            assertEquals(spec.key, "Voice", spec.group)
            assertFalse("${spec.key} must be unavailable with no microphone", spec.availableWhen(Capabilities()))
            assertTrue(
                "${spec.key} must be available with a microphone",
                spec.availableWhen(Capabilities(hasMicrophone = true)),
            )
        }
    }

    @Test fun `every voice setting reaches the Configure form`() {
        everyMicrophoneGatedVoiceSpec.forEach { spec -> assertFalse("${spec.key} must not be hidden", spec.hidden) }
        assertEquals(
            everyMicrophoneGatedVoiceSpec.map { it.key }.toSet(),
            SettingsRegistry.schemaVisibleSpecs().filter { it.group == "Voice" }.map { it.key }.toSet(),
        )
    }

    /**
     * A spec that became `transient` or lost its persist path would quietly discard every write.
     */
    @Test fun `the voice settings are readable, settable and persisted`() {
        everyMicrophoneGatedVoiceSpec.forEach { spec ->
            assertFalse("${spec.key} must still persist", spec.transient)
            assertFalse("${spec.key} must not be secret-redacted", spec.secret)
        }
        // A microphone-bearing panel still resolves the settings; only their rendering is withheld.
        val caps = Capabilities(hasMicrophone = true)
        everyMicrophoneGatedVoiceSpec.forEach { spec ->
            assertTrue("${spec.key} must remain capability-available", spec.availableWhen(caps))
        }
    }

    @Test fun `voice_enabled is an advanced live-apply switch, off by default and not a Home Assistant entity`() {
        assertEquals(SettingType.BOOL, voiceEnabled.type)
        assertEquals("false", voiceEnabled.default)
        assertEquals(Tier.ADVANCED, voiceEnabled.tier)
        assertTrue(voiceEnabled.liveApply)
        assertNull(voiceEnabled.ha)
    }

    @Test fun `voice_wake_words defaults to okay_nabu and validates known ids up to two entries`() {
        assertEquals(SettingType.STRING, wakeWords.type)
        assertEquals("""["okay_nabu"]""", wakeWords.default)
        assertEquals(
            "the default itself must validate",
            wakeWords.default,
            (SettingValue.validate(wakeWords, wakeWords.default) as Validation.Ok).normalized,
        )

        val ok = SettingValue.validate(wakeWords, """["hey_jarvis","alexa"]""") as Validation.Ok
        assertEquals("""["hey_jarvis","alexa"]""", ok.normalized)

        val emptyOk = SettingValue.validate(wakeWords, "[]") as Validation.Ok
        assertEquals("[]", emptyOk.normalized)
    }

    @Test fun `voice_wake_words rejects malformed JSON`() {
        assertTrue(SettingValue.validate(wakeWords, "not json") is Validation.Bad)
        assertTrue(SettingValue.validate(wakeWords, "{\"okay_nabu\":true}") is Validation.Bad)
        assertTrue(SettingValue.validate(wakeWords, "[\"okay_nabu\"") is Validation.Bad)
    }

    @Test fun `voice_wake_words accepts an imported model id`() {
        val ok = SettingValue.validate(wakeWords, """["okay_nabu","computer"]""") as Validation.Ok
        assertEquals("""["okay_nabu","computer"]""", ok.normalized)
    }

    @Test fun `voice_wake_words rejects an id that is not a wake-word id`() {
        listOf("Computer", "1computer", "hey-jarvis", "", "a" + "b".repeat(64)).forEach { id ->
            val bad = SettingValue.validate(wakeWords, """["okay_nabu","$id"]""") as Validation.Bad
            assertTrue(id, bad.reason.contains("\"$id\""))
        }
        // The longest id the shape admits is 64 characters.
        assertTrue(SettingValue.validate(wakeWords, """["a${"b".repeat(63)}"]""") is Validation.Ok)
    }

    @Test fun `voice_wake_words rejects more than thirty-two entries`() {
        val ids = (1..33).map { "wake_$it" }
        val bad = SettingValue.validate(wakeWords, JSONArray(ids).toString()) as Validation.Bad
        assertTrue(bad.reason.contains("at most"))
        assertTrue(SettingValue.validate(wakeWords, JSONArray(ids.take(32)).toString()) is Validation.Ok)
    }

    @Test fun `voice_wake_words rejects a duplicate entry`() {
        assertTrue(SettingValue.validate(wakeWords, """["okay_nabu","okay_nabu"]""") is Validation.Bad)
    }

    @Test fun `voice_wake_words rejects a non-string entry`() {
        assertTrue(SettingValue.validate(wakeWords, "[1]") is Validation.Bad)
    }

    @Test fun `voice_pipelines defaults to an empty object and validates a known-key string map`() {
        assertEquals(SettingType.STRING, pipelines.type)
        assertEquals("{}", pipelines.default)
        assertEquals(
            "{}",
            (SettingValue.validate(pipelines, pipelines.default) as Validation.Ok).normalized,
        )

        val ok = SettingValue.validate(
            pipelines,
            """{"hey_jarvis":"assist_pipeline_1","okay_nabu":""}""",
        ) as Validation.Ok
        // Keys are re-serialized sorted, so the round trip is stable regardless of request order.
        assertEquals("""{"hey_jarvis":"assist_pipeline_1","okay_nabu":""}""", ok.normalized)
    }

    @Test fun `voice_pipelines rejects malformed JSON`() {
        assertTrue(SettingValue.validate(pipelines, "not json") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, "[\"okay_nabu\"]") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, "{\"okay_nabu\":\"x\"") is Validation.Bad)
    }

    @Test fun `voice_pipelines accepts an imported wake word key`() {
        val ok = SettingValue.validate(pipelines, """{"computer":"assist_pipeline_1"}""") as Validation.Ok
        assertEquals("""{"computer":"assist_pipeline_1"}""", ok.normalized)
    }

    @Test fun `voice_pipelines rejects a key that is not a wake-word id`() {
        val bad = SettingValue.validate(pipelines, """{"Computer":"assist_pipeline_1"}""") as Validation.Bad
        assertTrue(bad.reason.contains("Computer"))
    }

    @Test fun `voice_pipelines rejects a non-string value`() {
        assertTrue(SettingValue.validate(pipelines, """{"okay_nabu":1}""") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, """{"okay_nabu":null}""") is Validation.Bad)
        assertTrue(SettingValue.validate(pipelines, """{"okay_nabu":["x"]}""") is Validation.Bad)
    }

    @Test fun `voice_audio_source is an enum defaulting to voice_recognition`() {
        assertEquals(SettingType.ENUM, audioSource.type)
        assertEquals("voice_recognition", audioSource.default)
        assertEquals(listOf("voice_recognition", "mic", "voice_communication"), audioSource.options)
    }

    @Test fun `voice_sensitivity is an enum defaulting to normal and documents its meaning`() {
        assertEquals(SettingType.ENUM, sensitivity.type)
        assertEquals("normal", sensitivity.default)
        assertEquals(listOf("low", "normal", "high"), sensitivity.options)
        assertTrue(sensitivity.help.contains("offset"))
    }

    @Test fun `the voice phase is the satellite's state in Home Assistant, not a panel setting`() {
        assertNull(SettingsRegistry.spec("voice_state"))
    }
}
