package io.panelassistant.android.device.profile

import io.panelassistant.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLogNoiseTest {
    private fun raw(logging: String = ""): String = ProfileYaml.serialize(testProfileDocument()) + logging

    private fun parse(raw: String): ProfileDocument {
        val parsed = ProfileYaml.parse(raw)
        assertNotNull(parsed.issues.toString(), parsed.document)
        val document = requireNotNull(parsed.document)
        assertEquals(emptyList<ProfileIssue>(), ProfileValidator.validate(document, BuildConfig.VERSION_NAME, bundled = false))
        return document
    }

    private fun profile(document: ProfileDocument) = DataDeviceProfile(document, "", "test-revision", false)

    @Test fun declaredCoreRuleSurvivesImportRuntimeProjectionAndExport() {
        val document = parse(raw("logging:\n  known_noise: [selinux-permissive]\n"))
        assertEquals(setOf("selinux-permissive"), profile(document).knownLogNoise)
        val exported = ProfileYaml.serialize(document)
        assertTrue(exported, exported.contains("logging:"))
        assertEquals(document, parse(exported))
        assertEquals(setOf("selinux-permissive"), profile(parse(exported)).knownLogNoise)
    }

    @Test fun malformedOrUnknownRulesAreRejectedAtTheirLoggingPath() {
        val invalid = listOf(
            "logging: null\n" to "logging",
            "logging: selinux-permissive\n" to "logging",
            "logging:\n  regex: .*\n" to "logging.regex",
            "logging:\n  known_noise: null\n" to "logging.known_noise",
            "logging:\n  known_noise: selinux-permissive\n" to "logging.known_noise",
            "logging:\n  known_noise: [true]\n" to "logging.known_noise[0]",
            "logging:\n  known_noise: [unknown-rule]\n" to "logging.known_noise[0]",
            "logging:\n  known_noise: [selinux-permissive, selinux-permissive]\n" to "logging.known_noise[1]",
        )
        invalid.forEach { (logging, path) ->
            val result = ProfileYaml.parse(raw(logging))
            val issues = result.issues + result.document?.let {
                ProfileValidator.validate(it, BuildConfig.VERSION_NAME, bundled = false)
            }.orEmpty()
            assertTrue("$logging produced $issues", issues.any { it.severity == ProfileIssueSeverity.ERROR && it.path == path })
        }
    }

    @Test fun undeclaredAndEmptyLoggingFilterNothingAndExportWithoutANewSection() {
        for (logging in listOf("", "logging: {}\n", "logging:\n  known_noise: []\n")) {
            val document = parse(raw(logging))
            assertTrue(profile(document).knownLogNoise.isEmpty())
            val exported = ProfileYaml.serialize(document)
            assertFalse(exported, exported.contains("logging:"))
            assertEquals(document, parse(exported))
        }
    }

    @Test fun bundledRulesAreLimitedToProfilesWithMeasuredPermissiveAudits() {
        val optedIn = BundledProfileFixtures.bundled.mapNotNull { source ->
            source.profile().knownLogNoise.takeIf { it.isNotEmpty() }?.let { source.document.id to it }
        }.toMap()
        assertEquals(
            mapOf(
                "nspanel-pro" to setOf("selinux-permissive"),
                "shelly-wall-display-x2i" to setOf("selinux-permissive"),
                "wf1589t" to setOf("selinux-permissive"),
            ),
            optedIn,
        )
    }
}
