package io.github.maxlyth.hapaneld.http

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class AutoSleepHttpApiTest {
    @Test fun `panel source never requires an HA Area but switching an active policy to HA does`() {
        assertFalse(autoSleepRequiresHaAdmission(false, "panel", true, "panel"))
        assertFalse(autoSleepRequiresHaAdmission(true, "home_assistant", true, "panel"))
        assertTrue(autoSleepRequiresHaAdmission(false, "home_assistant", true, "home_assistant"))
        assertTrue(autoSleepRequiresHaAdmission(true, "panel", true, "home_assistant"))
        assertFalse(autoSleepRequiresHaAdmission(true, "home_assistant", true, "home_assistant"))
        assertFalse(autoSleepRequiresHaAdmission(true, "panel", false, "home_assistant"))
    }

    @Test fun `config prerequisite rejections are structured JSON`() {
        val parsed = JSONObject(autoSleepConfigErrorJson(
            "auto-sleep-area-required",
            "Assign this panel to a Home Assistant Area before enabling Auto sleep.",
        ))

        assertFalse(parsed.getBoolean("ok"))
        assertEquals("auto-sleep-area-required", parsed.getString("error"))
        assertTrue(parsed.getString("message").contains("Home Assistant Area"))
    }

    @Test fun `unwired runtime returns a compact readable status`() {
        val api = AutoSleepHttpApi.UNAVAILABLE

        val status = JSONObject(api.statusJson())
        assertFalse(status.getBoolean("available"))
        assertEquals("unavailable", status.getString("phase"))
        assertEquals(0, status.getInt("source_count"))
        assertFalse(status.getBoolean("manual_suppression"))
        assertEquals("", status.getString("detail"))
    }

    @Test fun `history hours default and bounds are strict`() {
        assertEquals(6, autoSleepHistoryHours(null))
        assertEquals(1, autoSleepHistoryHours("1"))
        assertEquals(24, autoSleepHistoryHours("24"))
        assertEquals(48, autoSleepHistoryHours("48"))
        listOf("", "0", "49", "six").forEach { raw ->
            assertTrue(runCatching { autoSleepHistoryHours(raw) }.isFailure)
        }
    }

    @Test fun `unwired history is categorical and explicit about exclusions`() = runTest {
        val history = JSONObject(AutoSleepHttpApi.UNAVAILABLE.historyJson(6))

        assertFalse(history.getBoolean("available"))
        assertTrue(history.getBoolean("area_sources_only"))
        assertEquals("selected_area_sources", history.getString("source_scope"))
        assertEquals(60_000L, history.getLong("bucket_ms"))
        assertEquals(0, history.getInt("source_count"))
        assertFalse(history.has("sources"))
        assertEquals(0, history.getJSONArray("segments").length())
        val exclusions = history.getJSONArray("exclusions")
        assertTrue((0 until exclusions.length()).any { exclusions.getString(it) == "panel_proximity" })
    }
}
