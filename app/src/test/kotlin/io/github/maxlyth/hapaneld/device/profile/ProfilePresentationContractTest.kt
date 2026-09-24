package io.github.maxlyth.hapaneld.device.profile

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

    @Test fun `every admitted code is owned by a backend construction branch`() {
        val sources = listOf(
            "ProfileContracts.kt",
            "ProfileDraftFactory.kt",
            "ProfileValidator.kt",
            "ProfileYaml.kt",
            "RuntimeProfileRegistry.kt",
        ).associateWith { name ->
            File("src/main/kotlin/io/github/maxlyth/hapaneld/device/profile/$name").readText()
        }.toMutableMap().apply {
            put(
                "ProfileRoutes.kt",
                File("src/main/kotlin/io/github/maxlyth/hapaneld/http/ProfileRoutes.kt").readText(),
            )
        }
        val contracts = requireNotNull(sources["ProfileContracts.kt"])
        val definitionStart = contracts.indexOf("val SUPPORTED_CODES: Set<String> = setOf(")
        val definitionEnd = contracts.indexOf("/** Privileged or unusually consequential behavior", definitionStart)
        sources["ProfileContracts.kt"] = contracts.removeRange(definitionStart, definitionEnd)

        val ownedLiterals = sources.values.flatMapTo(mutableSetOf()) { source ->
            constructionBodies(source).flatMap { body ->
                CODE_LITERAL.findAll(body).map { it.groupValues[1] }.toList()
            }
        }
        val dynamicCodes = setOf(
            "activation-rolled-back-unhealthy-auto",
            "activation-rolled-back-unhealthy-pinned",
            "repin-persist-failed-auto",
            "repin-persist-failed-pinned",
        )
        assertTrue(requireNotNull(sources["RuntimeProfileRegistry.kt"]).contains("rollbackPresentation(\"activation-rolled-back-unhealthy\""))
        assertTrue(requireNotNull(sources["RuntimeProfileRegistry.kt"]).contains("rollbackPresentation(\"repin-persist-failed\""))
        val unowned = ProfilePresentation.SUPPORTED_CODES - ownedLiterals - dynamicCodes
        val unknown = ownedLiterals - ProfilePresentation.SUPPORTED_CODES
        assertTrue("Admitted codes without construction branches: $unowned", unowned.isEmpty())
        assertTrue("Construction branches with unadmitted codes: $unknown", unknown.isEmpty())
    }

    @Test fun `construction audit detects a removed callsite`() {
        val source = File(
            "src/main/kotlin/io/github/maxlyth/hapaneld/device/profile/RuntimeProfileRegistry.kt",
        ).readText()
        val marker = "ProfilePresentation(\"emergency-profile-in-use\")"
        assertTrue(source.contains(marker))
        assertTrue(ownedCodes(source).contains("emergency-profile-in-use"))
        assertTrue(!ownedCodes(source.replace(marker, "null")).contains("emergency-profile-in-use"))
    }

    private fun ownedCodes(source: String): Set<String> = constructionBodies(source).flatMapTo(mutableSetOf()) { body ->
        CODE_LITERAL.findAll(body).map { it.groupValues[1] }.toList()
    }

    /**
     * Only inspect expressions that actually construct or transport presentation metadata. This is
     * deliberately narrower than collecting every kebab-case literal in the source: a dead registry
     * entry must not be kept alive by a comment, a compatibility message, or an unrelated lookup.
     */
    private fun constructionBodies(source: String): List<String> = buildList {
        CONSTRUCTION_CALL.findAll(source).forEach { match ->
            val open = source.indexOf('(', match.range.first)
            if (open < 0) return@forEach
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in open until source.length) {
                val character = source[index]
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> quoted = false
                    }
                } else {
                    when (character) {
                        '"' -> quoted = true
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                add(source.substring(open + 1, index))
                                break
                            }
                        }
                    }
                }
            }
        }
        KEY_PRESENTATION_ASSIGNMENT.findAll(source).forEach { add(it.value) }
        PRESENTATION_PAIR.findAll(source).forEach { add(it.value) }
        CODE_MESSAGE_PAIR.findAll(source).forEach { add(it.value) }
    }

    private companion object {
        val CODE_LITERAL = Regex("\"([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\"")
        val CONSTRUCTION_CALL = Regex(
            "\\b(?:ProfilePresentation|backupIssue|issue|error|reject|rejected|warn|" +
                "respondProfileError|respondProfileTextError|receiveBoundedUtf8)\\s*\\(",
        )
        val KEY_PRESENTATION_ASSIGNMENT = Regex("KEY_PRESENTATION_CODE\\s+to\\s+\"[a-z][a-z0-9-]+\"")
        val PRESENTATION_PAIR = Regex("\"[^\"]+[.!?]\"\\s+to\\s+\"[a-z][a-z0-9-]+\"")
        val CODE_MESSAGE_PAIR = Regex("\"[a-z][a-z0-9-]+\"\\s+to\\s+\"[^\"]+[.!?]\"")
    }
}

/** Resolves the production English catalogue exactly as the browser's closed presentation map does. */
internal fun assertAuthoritativeEnglishMatches(
    compatibilityProse: String,
    presentation: ProfilePresentation,
) {
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
