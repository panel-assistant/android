package io.github.maxlyth.hapaneld.http

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

class AutoBrightnessHttpApiTest {
    @Test fun `unwired API is readable but closes mutations`() = runTest {
        val api = AutoBrightnessHttpApi.UNAVAILABLE

        assertFalse(JSONObject(api.statusJson()).getBoolean("available"))
        val status = JSONObject(api.statusJson())
        val history = JSONObject(api.historyJson())
        assertTrue(status.isNull("sourceRevision"))
        assertTrue(history.isNull("sourceRevision"))
        assertTrue(history.isNull("latestEpochMinute"))
        assertEquals(JSONArray().toString(), history.getJSONArray("points").toString())
        assertEquals(JSONArray().toString(), JSONObject(api.haSourcesJson("", 100)).getJSONArray("items").toString())
        val validation = api.validateHaSource("sensor.room_illuminance")
        assertEquals(503, validation.action.statusCode)
        assertFalse(JSONObject(validation.action.json).getBoolean("ok"))
        listOf(
            api.selectHaSource(null),
            api.resetHistory(),
            api.resumeFullAuto(),
        ).forEach { action ->
            assertEquals(503, action.statusCode)
            assertFalse(JSONObject(action.json).getBoolean("ok"))
        }
    }

    @Test fun `history query defaults and enforces public bounds`() {
        // The accepted cases go through runCatching so that narrowing a bound turns this assertion RED
        // rather than throwing out of the test. A bare call proves the bound only by not exploding, and
        // an escaped IllegalArgumentException is an error, not a failure — a mutation battery cannot
        // credit it, and neither should a reader.
        assertEquals(
            AutoBrightnessHistoryParameters(168, null, null, null),
            runCatching { autoBrightnessHistoryParameters(null, null) }.getOrNull(),
        )
        assertEquals(
            AutoBrightnessHistoryParameters(1, 0, 4, null),
            runCatching { autoBrightnessHistoryParameters("1", "0", "4") }.getOrNull(),
        )
        assertEquals(
            "the published ceiling must be accepted, not merely not-rejected",
            AutoBrightnessHistoryParameters(168, 100, 99, null),
            runCatching { autoBrightnessHistoryParameters("168", "100", "99") }.getOrNull(),
        )

        assertEquals(AutoBrightnessHistoryParameters(168, null, 20, 60),
            autoBrightnessHistoryParameters(null, null, "20", "60"))
        listOf("4", "101", "bright").forEach { maximum ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(null, null, null, maximum) }.isFailure)
        }
        listOf("20", "19").forEach { maximum ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(null, null, "20", maximum) }.isFailure)
        }

        listOf("0", "169", "not-a-number").forEach { hours ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(hours, null) }.isFailure)
        }
        listOf("-1", "101", "balanced").forEach { sensitivity ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(null, sensitivity) }.isFailure)
        }
        listOf("3", "100", "balanced").forEach { minimum ->
            assertTrue(runCatching { autoBrightnessHistoryParameters(null, null, minimum) }.isFailure)
        }
    }

    @Test fun `action result keeps status and JSON body together`() {
        val action = AutoBrightnessHttpAction.ok("""{"ok":true,"state":"learning"}""")

        assertEquals(200, action.statusCode)
        assertTrue(JSONObject(action.json).getBoolean("ok"))
        assertEquals("learning", JSONObject(action.json).getString("state"))
        assertTrue(runCatching { AutoBrightnessHttpAction(199, "{}") }.isFailure)
        assertTrue(runCatching { AutoBrightnessHttpAction(200, "") }.isFailure)
    }
}
