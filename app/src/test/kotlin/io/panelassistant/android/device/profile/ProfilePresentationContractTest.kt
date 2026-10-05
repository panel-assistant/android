package io.panelassistant.android.device.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.json.JSONObject

class ProfilePresentationContractTest {
    @Test fun `every admitted code has an exact constructible parameter contract`() {
        assertEquals(209, ProfilePresentation.SUPPORTED_CODES.size)
        ProfilePresentation.SUPPORTED_CODES.forEach { code ->
            val names = requireNotNull(ProfilePresentation.expectedParams(code))
            assertTrue("$code has too many parameters", names.size <= 8)
            val presentation = ProfilePresentation(code, names.associateWith { "sample" })
            assertEquals(code, presentation.code)
            assertEquals(names, presentation.params.keys)
        }
    }

    @Test fun `invalid or oversized presentation parameters cannot be admitted`() {
        assertTrue(runCatching { ProfilePresentation("unknown-value") }.isFailure)
        assertTrue(runCatching { ProfilePresentation("unknown-value", mapOf("wrong" to "value")) }.isFailure)
        assertTrue(
            runCatching {
                ProfilePresentation("unknown-value", mapOf("value" to "x".repeat(513)))
            }.isFailure,
        )
    }
}

/** Resolves the production English catalogue exactly as the browser's closed presentation map does. */
internal fun assertAuthoritativeEnglishMatches(
    compatibilityProse: String,
    presentation: ProfilePresentation,
) {
    // Source-text reason: loads the shipped English catalogue as input data (catalogue contract).
    val strings = JSONObject(File("src/main/assets/i18n/en.json").readText()).getJSONObject("strings")
    val keys = listOf("profiles.issue.${presentation.code}", "profiles.result.${presentation.code}")
        .filter(strings::has)
    assertEquals("presentation code must resolve to exactly one authoritative English record", 1, keys.size)
    var rendered = strings.getJSONObject(keys.single()).getString("text")
    presentation.params.forEach { (name, value) -> rendered = rendered.replace("{$name}", value) }
    assertTrue("all authoritative English placeholders must be resolved: $rendered", !PLACEHOLDER.containsMatchIn(rendered))
    assertEquals(compatibilityProse, rendered)
}

private val PLACEHOLDER = Regex("\\{[a-z_]+}")
