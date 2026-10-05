package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class InstallPresentationTest {
    @Test fun frozenVocabularyAndCanonicalEnvelopeAreExact() {
        assertEquals(114, InstallPresentation.SUPPORTED_CODES.size)
        val presentation = InstallPresentation(
            "status-update-available",
            mapOf(
                "component" to "paneld",
                "current" to "0.9.6",
                "latest" to "0.9.7",
                "release_url" to "https://example.invalid/release",
            ),
        )

        val json = JSONObject(presentation.json())
        assertEquals(setOf("code", "params"), json.keys().asSequence().toSet())
        assertEquals("status-update-available", json.getString("code"))
        assertEquals("paneld", json.getJSONObject("params").getString("component"))
    }

    @Test fun codeSpecificRequiredOptionalAndUnknownParametersFailClosed() {
        assertNull(InstallPresentation.create("unknown-code"))
        assertNull(InstallPresentation.create("operation-working"))
        assertNull(InstallPresentation.create("restore-completed", mapOf("detail" to "raw exception")))
        assertNotNull(InstallPresentation.create("status-storage-warning"))
        assertNotNull(InstallPresentation.create("status-storage-warning", mapOf("usable_bytes" to "42")))
        assertNull(InstallPresentation.create("status-storage-warning", mapOf("count" to "1")))
    }

    @Test fun parameterDomainsAndEnvelopeBoundsAreEnforced() {
        assertNull(InstallPresentation.create("operation-working", mapOf("owner" to "raw English")))
        assertNull(InstallPresentation.create("restore-completed-with-state", mapOf("count" to "01")))
        assertNull(InstallPresentation.create("package-uninstalled", mapOf("package" to "not a package")))
        assertNull(
            InstallPresentation.create(
                "status-update-available",
                mapOf(
                    "component" to "paneld",
                    "current" to "1",
                    "latest" to "2",
                    "release_url" to "javascript:alert(1)",
                ),
            ),
        )
        assertNull(
            InstallPresentation.create(
                "status-storage-warning",
                mapOf(
                    "usable_bytes" to "1".repeat(19),
                    "total_bytes" to "2".repeat(19),
                    "used_percent" to "99.${"9".repeat(513)}",
                    "database_bytes" to "3".repeat(19),
                    "wal_bytes" to "4".repeat(19),
                ),
            ),
        )
        assertTrue(
            InstallPresentation("operation-working", mapOf("owner" to "paneld"))
                .json().toByteArray(Charsets.UTF_8).size <= InstallPresentation.MAX_SERIALIZED_BYTES,
        )
    }

    @Test fun parametersAreSnapshottedAfterAdmission() {
        val mutable = linkedMapOf("owner" to "paneld")
        val presentation = InstallPresentation("operation-working", mutable)
        mutable["owner"] = "companion"

        assertEquals("paneld", presentation.params["owner"])
        assertFalse(presentation.params === mutable)

        val parameterMutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (presentation.params as MutableMap<String, String>)["owner"] = "companion"
        }.exceptionOrNull()
        assertTrue(parameterMutation is UnsupportedOperationException)
        assertEquals("paneld", JSONObject(presentation.json()).getJSONObject("params").getString("owner"))

        val vocabularyMutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (InstallPresentation.SUPPORTED_CODES as MutableSet<String>).add("attacker-code")
        }.exceptionOrNull()
        assertTrue(vocabularyMutation is UnsupportedOperationException)
        assertNull(InstallPresentation.create("attacker-code"))
    }

    @Test fun installOutcomeKeepsLegacyConstructionAndAddsOptionalPresentation() {
        assertEquals(InstallOutcome.Rejected("legacy"), InstallOutcome.Rejected("legacy"))
        assertNull((InstallOutcome.Retryable("legacy") as InstallOutcome.Failure).presentation)
        val presentation = InstallPresentation(
            "install-retryable-failure",
            mapOf("component" to "webview"),
        )
        assertEquals(
            presentation,
            (InstallOutcome.Retryable("legacy", presentation) as InstallOutcome.Failure).presentation,
        )
    }
}
