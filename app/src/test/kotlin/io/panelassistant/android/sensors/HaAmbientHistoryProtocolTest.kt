package io.panelassistant.android.sensors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HaAmbientHistoryProtocolTest {
    @Test fun `history request is exact bounded and disables significance filtering`() {
        val start = Instant.parse("2026-07-12T10:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-07-19T10:00:00Z").toEpochMilli()

        val path = haHistoryPath(listOf(ENTITY), start, end)

        assertTrue(path.startsWith("/api/history/period/2026-07-12T10:00:00Z?"))
        assertTrue("filter_entity_id=sensor.room_illuminance" in path)
        assertTrue("end_time=2026-07-19T10%3A00%3A00Z" in path)
        assertTrue("minimal_response" in path && "no_attributes" in path)
        assertTrue("significant_changes_only=0" in path)
    }

    @Test fun `several entities are one sorted, comma-joined filter`() {
        val path = haHistoryPath(setOf("sensor.b", "binary_sensor.a"), 0L, 1_000L)

        assertTrue(path.startsWith("/api/history/period/1970-01-01T00:00:00Z?end_time=1970-01-01T00%3A00%3A01Z&"))
        assertTrue("filter_entity_id=binary_sensor.a%2Csensor.b&" in path)
    }

    @Test fun `minimal history is projected as a time weighted step function`() {
        val start = Instant.parse("2026-07-19T10:00:00Z").toEpochMilli()
        val response = history(
            state("10", "2026-07-19T09:59:00Z", entity = ENTITY),
            state("30", "2026-07-19T10:01:30Z"),
        )

        val rows = HaAmbientHistoryProtocol.parse(response, ENTITY, start, start + 3 * MINUTE)

        assertEquals(3, rows.size)
        assertEquals(10.0, rows[0].luxIntegral / rows[0].coverageMs, 0.001)
        assertEquals(20.0, rows[1].luxIntegral / rows[1].coverageMs, 0.001)
        assertEquals(10.0, rows[1].minLux, 0.001)
        assertEquals(30.0, rows[1].maxLux, 0.001)
        assertEquals(30.0, rows[2].lastLux, 0.001)
        assertTrue(rows.all { it.coverageMs == MINUTE && it.baselineCoverageMs == it.coverageMs })
    }

    @Test fun `unavailable states create gaps rather than invented light`() {
        val start = Instant.parse("2026-07-19T10:00:00Z").toEpochMilli()
        val rows = HaAmbientHistoryProtocol.parse(
            history(
                state("10", "2026-07-19T10:00:00Z", entity = ENTITY),
                state("unavailable", "2026-07-19T10:00:30Z"),
                state("20", "2026-07-19T10:01:30Z"),
            ),
            ENTITY,
            start,
            start + 2 * MINUTE,
        )

        assertEquals(2, rows.size)
        assertEquals(30_000L, rows[0].coverageMs)
        assertEquals(30_000L, rows[1].coverageMs)
        assertEquals(20.0, rows[1].lastLux, 0.001)
    }

    @Test fun `equal timestamps are last wins and out of order history is rejected`() {
        val start = Instant.parse("2026-07-19T10:00:00Z").toEpochMilli()
        val duplicate = HaAmbientHistoryProtocol.parse(
            history(
                state("10", "2026-07-19T10:00:00Z", entity = ENTITY),
                state("40", "2026-07-19T10:00:00Z"),
            ),
            ENTITY,
            start,
            start + MINUTE,
        )
        assertEquals(40.0, duplicate.single().lastLux, 0.001)

        val error = runCatching {
            HaAmbientHistoryProtocol.parse(
                history(
                    state("10", "2026-07-19T10:00:30Z", entity = ENTITY),
                    state("20", "2026-07-19T10:00:00Z"),
                ),
                ENTITY,
                start,
                start + MINUTE,
            )
        }.exceptionOrNull()
        assertTrue(error is HaProtocolException)
    }

    @Test fun `history for another entity is rejected without persisting identifiers`() {
        val start = Instant.parse("2026-07-19T10:00:00Z").toEpochMilli()
        val error = runCatching {
            HaAmbientHistoryProtocol.parse(
                history(state("10", "2026-07-19T10:00:00Z", entity = "sensor.other")),
                ENTITY,
                start,
                start + MINUTE,
            )
        }.exceptionOrNull()
        assertTrue(error is HaProtocolException)
    }

    private fun history(vararg states: JSONObject) = JSONArray().put(JSONArray(states.toList()))

    private fun state(value: String, changed: String, entity: String? = null) = JSONObject()
        .apply { if (entity != null) put("entity_id", entity) }
        .put("state", value)
        .put("last_changed", changed)

    private companion object {
        const val ENTITY = "sensor.room_illuminance"
        const val MINUTE = 60_000L
    }
}
