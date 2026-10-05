package io.panelassistant.android.http

import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallI18nProjectionTest {
    // Source-text reason: loads the shipped i18n catalogues as input data.
    private val loader = CatalogueLoader { path -> TestSources.asset(path).readText() }

    @Test fun `Install projection carries real German text and provenance for closed wire tokens`() {
        val payload = JSONObject(
            browserI18nPayload(loader.strings("de"), setOf("shell.", "install.", "runtime.mdns.")),
        )
        val strings = payload.getJSONObject("strings")
        val expected = mapOf(
            "install.radio_state.degraded_unjoined" to "aktiviert, aber keinem Netz beigetreten",
            "install.config_import_status.wrong_kind_or_schema" to "falscher Pakettyp oder falsches Schema",
            "install.restore_outcome.rollback_failed" to "Rollback fehlgeschlagen",
        )

        assertEquals("de", payload.getString("locale"))
        assertTrue(payload.has("languages"))
        val languages = payload.getJSONObject("languages")
        expected.forEach { (key, translation) ->
            assertEquals("real catalogue text for $key", translation, strings.getString(key))
            assertEquals("real catalogue provenance for $key", "de", languages.getString(key))
        }
        assertEquals("de", languages.getString("runtime.mdns.not_running"))
    }

    @Test fun `Install projection preserves raw compatibility tokens for non-localized records`() {
        val fallbackKey = "install.restore_outcome.rollback_failed"
        val downgradedTarget = JSONObject(TestSources.asset("i18n/de.json").readText()).apply {
            getJSONObject("strings").getJSONObject(fallbackKey).put("state", "machine-draft")
        }
        val mixedLoader = CatalogueLoader { path ->
            if (path == "i18n/de.json") downgradedTarget.toString() else TestSources.asset(path).readText()
        }
        val payload = JSONObject(
            browserI18nPayload(mixedLoader.strings("de"), setOf("shell.", "install.")),
        )

        assertTrue(payload.has("languages"))
        assertEquals(
            "aktiviert, aber keinem Netz beigetreten",
            closedWireToken(payload, "install.radio_state.degraded_unjoined", "degraded_unjoined"),
        )
        assertEquals("de", payload.getJSONObject("languages").getString("install.radio_state.degraded_unjoined"))
        assertEquals("en", payload.getJSONObject("languages").getString(fallbackKey))
        assertEquals("rollback_failed", closedWireToken(payload, fallbackKey, "rollback_failed"))
    }

    @Test fun `unrelated browser projections retain their compact payload`() {
        val payload = JSONObject(browserI18nPayload(loader.strings("de"), setOf("shell.")))

        assertFalse(payload.has("languages"))
        assertTrue(payload.getJSONObject("strings").has("shell.nav.install"))
    }

    private fun closedWireToken(payload: JSONObject, key: String, raw: String): String =
        if (payload.getJSONObject("languages").optString(key) == payload.getString("locale")) {
            payload.getJSONObject("strings").getString(key)
        } else {
            raw
        }
}
