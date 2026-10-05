package io.panelassistant.android.sensors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAmbientLuxProtocolTest {

    @Test fun `compressed exact entity updates retain attributes and timestamps`() {
        val entity = "sensor.room_illuminance"
        val projection = HaCompressedEntityProjection(entity)
        val attributes = JSONObject().put("device_class", "illuminance").put("unit_of_measurement", "lx")
        val added = projection.apply(JSONObject().put(
            "a",
            JSONObject().put(entity, JSONObject().put("s", "12").put("a", attributes).put("lc", 1_768_644_000.0)),
        )) as HaExactSocketMessage.State
        val changed = projection.apply(JSONObject().put(
            "c",
            JSONObject().put(entity, JSONObject().put("+", JSONObject().put("s", "34.5").put("lu", 1_768_644_002.0))),
        )) as HaExactSocketMessage.State

        assertEquals("12", added.json.getString("state"))
        assertEquals("2026-01-17T10:00:00Z", added.json.getString("last_updated"))
        assertEquals("34.5", changed.json.getString("state"))
        assertEquals("lx", changed.json.getJSONObject("attributes").getString("unit_of_measurement"))
        assertEquals("2026-01-17T10:00:02Z", changed.json.getString("last_updated"))
        assertTrue(projection.apply(JSONObject().put("r", JSONArray().put(entity))) is HaExactSocketMessage.Missing)
    }

    @Test fun `subscribe first REST hydration cannot overwrite newer stream state`() {
        val samples = mutableListOf<HaAmbientLuxSample>()
        var received = 1_000L
        val gate = HaLuxSampleGate("sensor.room_illuminance", { received++ }, samples::add)
        val rest = state("sensor.room_illuminance", "10", "2026-07-17T10:00:00Z")
        val newerStream = state("sensor.room_illuminance", "50", "2026-07-17T10:00:02Z")
        val staleBuffered = state("sensor.room_illuminance", "20", "2026-07-17T09:59:59Z")

        assertTrue(gate.accept(rest, HaAmbientSampleOrigin.REST_INITIAL))
        assertTrue(gate.accept(newerStream, HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(staleBuffered, HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(newerStream, HaAmbientSampleOrigin.WEBSOCKET))

        assertEquals(listOf(10.0, 50.0), samples.map(HaAmbientLuxSample::lux))
        assertEquals(listOf(HaAmbientSampleOrigin.REST_INITIAL, HaAmbientSampleOrigin.WEBSOCKET), samples.map(HaAmbientLuxSample::origin))
    }

    @Test fun `samples reject wrong entity unavailable negative and non-finite values`() {
        val samples = mutableListOf<HaAmbientLuxSample>()
        val gate = HaLuxSampleGate("sensor.room_illuminance", { 0L }, samples::add)

        assertFalse(gate.accept(state("sensor.other", "1", "2026-07-17T10:00:00Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(state("sensor.room_illuminance", "unavailable", "2026-07-17T10:00:01Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(state("sensor.room_illuminance", "-1", "2026-07-17T10:00:02Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertFalse(gate.accept(state("sensor.room_illuminance", "NaN", "2026-07-17T10:00:03Z"), HaAmbientSampleOrigin.WEBSOCKET))
        assertTrue(samples.isEmpty())
    }

    @Test fun `candidate discovery keeps bounded illuminance projection only`() {
        val states = JSONArray()
            .put(
                state("sensor.window_lux", "123.4", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("device_class", "illuminance").put("unit_of_measurement", "lx").put("friendly_name", "Window lux")),
            )
            .put(
                state("sensor.ceiling_light", "unavailable", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("unit_of_measurement", "lux").put("friendly_name", "Ceiling")),
            )
            .put(
                state("sensor.temperature", "21", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("device_class", "temperature").put("unit_of_measurement", "C")),
            )
            .put(
                state("light.fake_lux", "10", "2026-07-17T10:00:00Z")
                    .put("attributes", JSONObject().put("device_class", "illuminance")),
            )

        val candidates = HaAmbientLuxProtocol.candidates(states)

        assertEquals(listOf("sensor.ceiling_light", "sensor.window_lux"), candidates.map(HaAmbientLuxCandidate::entityId))
        assertFalse(candidates[0].available)
        assertNull(candidates[0].currentLux)
        assertTrue(candidates[1].available)
        assertEquals(123.4, candidates[1].currentLux!!, 0.0)
    }

    private fun state(entityId: String, value: String, updated: String) = JSONObject()
        .put("entity_id", entityId)
        .put("state", value)
        .put("last_updated", updated)
        .put("attributes", JSONObject().put("device_class", "illuminance").put("unit_of_measurement", "lx"))
}
