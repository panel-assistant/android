package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.http.HaAreaProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry shapes a panel meets in the field: registered by MQTT only, by Panel Assistant only (every
 * migrated panel, which matched nothing before this matcher), and by both. Fixtures follow the live
 * registry: a Panel Assistant device carries only `(panel_assistant, <entry_id>)`, its status sensor and
 * update entity are keyed by the entry id, and every native entity by `<discovery id>_<suffix>`.
 */
class HaPanelDeviceMatcherTest {
    private val did = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val otherDid = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private val entry = "01J00000000000000000000AAA"
    private val otherEntry = "01J00000000000000000000BBB"
    private val clonedAndroidId = "9f86d081884c7d65"

    // --- proving the Panel Assistant entry ------------------------------------------------------

    @Test fun `the entry is proven by a native entity keyed by this panel's discovery id`() {
        assertEquals(setOf(entry), HaPanelDeviceMatcher.panelAssistantEntryIds(probe(), did))
    }

    @Test fun `another panel's discovery id proves nothing about this panel`() {
        assertEquals(setOf(otherEntry), HaPanelDeviceMatcher.panelAssistantEntryIds(probe(), otherDid))
        assertEquals(emptySet<String>(), HaPanelDeviceMatcher.panelAssistantEntryIds(probe(), "0".repeat(64)))
    }

    @Test fun `a discovery id that merely prefixes another does not prove it`() {
        val longer = JSONObject().put("result", JSONObject()
            .put("sensor.x", entityRow("${did}ff_proximity", otherEntry)))
        assertEquals(emptySet<String>(), HaPanelDeviceMatcher.panelAssistantEntryIds(longer, did))
    }

    @Test fun `only a panel_assistant entity proves an entry`() {
        val foreign = JSONObject().put("result", JSONObject()
            .put("sensor.x", entityRow("${did}_proximity", entry, platform = "mqtt")))
        assertEquals(emptySet<String>(), HaPanelDeviceMatcher.panelAssistantEntryIds(foreign, did))
    }

    @Test fun `no discovery id or no probe proves nothing`() {
        assertEquals(emptySet<String>(), HaPanelDeviceMatcher.panelAssistantEntryIds(probe(), null))
        assertEquals(emptySet<String>(), HaPanelDeviceMatcher.panelAssistantEntryIds(probe(), " "))
        assertEquals(emptySet<String>(), HaPanelDeviceMatcher.panelAssistantEntryIds(null, did))
    }

    @Test fun `the probe samples only Panel Assistant entities and a bounded number per device`() {
        val rows = JSONArray()
            .put(display("sensor.status", "pa-device", "panel_assistant"))
            .put(display("binary_sensor.kitchen_motion", "motion-device", "mqtt"))
            .put(display("sensor.orphan", "", "panel_assistant"))
        (1..12).forEach { rows.put(display("sensor.other_%02d".format(it), "other-pa-device", "panel_assistant")) }
        val ids = HaPanelDeviceMatcher.probeEntityIds(JSONObject().put("result", JSONObject().put("entities", rows)))

        assertEquals(listOf("sensor.status") + (1..8).map { "sensor.other_%02d".format(it) }, ids)
        assertEquals(null, HaPanelDeviceMatcher.probeRequest(emptyList()))
        val request = checkNotNull(HaPanelDeviceMatcher.probeRequest(ids))
        assertEquals("config/entity_registry/get_entries", request.getString("type"))
        assertEquals(ids.size, request.getJSONArray("entity_ids").length())
    }

    @Test fun `the probe is skipped when no device is registered by Panel Assistant`() {
        assertFalse(HaPanelDeviceMatcher.hasPanelAssistantDevice(response(mqttDevice("m", "ha-paneld-uid-abc"))))
        assertTrue(HaPanelDeviceMatcher.hasPanelAssistantDevice(response(mqttDevice("m", "x"), paDevice("p", entry))))
    }

    @Test fun `the probe read asks only what it needs over the registry socket`() = kotlinx.coroutines.runBlocking {
        val display = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(display("binary_sensor.study_proximity", "pa-device", "panel_assistant"))))
        val sent = mutableListOf<JSONObject>()
        val request: suspend (JSONObject) -> JSONObject = { command ->
            sent += command
            if (command.getString("type") == "config/entity_registry/list_for_display") display else probe()
        }

        assertEquals(null, HaPanelDeviceMatcher.readProbe(request, response(mqttDevice("m", "ha-paneld-uid-abc"))))
        assertEquals("an MQTT-only registry costs no extra reads", 0, sent.size)

        val paOnly = response(paDevice("pa-device", entry))
        assertEquals(setOf(entry), HaPanelDeviceMatcher.panelAssistantEntryIds(
            HaPanelDeviceMatcher.readProbe(request, paOnly, display), did,
        ))
        assertEquals(listOf("config/entity_registry/get_entries"), sent.map { it.getString("type") })
        assertEquals("binary_sensor.study_proximity", sent.single().getJSONArray("entity_ids").getString(0))

        sent.clear()
        assertEquals(setOf(entry), HaPanelDeviceMatcher.panelAssistantEntryIds(
            HaPanelDeviceMatcher.readProbe(request, paOnly), did,
        ))
        assertEquals(
            listOf("config/entity_registry/list_for_display", "config/entity_registry/get_entries"),
            sent.map { it.getString("type") },
        )
    }

    // --- the three registry shapes ------------------------------------------------------------

    @Test fun `mqtt only resolves by the minted identity and then by the panel id`() {
        val minted = listOf(mqttDevice("uid-device", "ha-paneld-uid-abc"))
        val legacy = listOf(mqttDevice("legacy-device", "ha-paneld-panel"))

        assertEquals("uid-device", found(minted, entries = emptySet()))
        assertEquals("legacy-device", found(legacy, entries = emptySet()))
    }

    @Test fun `panel_assistant only resolves through the proven entry`() {
        val devices = listOf(paDevice("pa-device", entry), paDevice("other-pa-device", otherEntry))

        assertEquals("pa-device", found(devices, entries = setOf(entry)))
        assertEquals(
            "without the proof a Panel Assistant device is nobody's",
            HaPanelDeviceMatcher.Match.Missing,
            HaPanelDeviceMatcher.preferred(devices, "abc", "panel", emptySet()),
        )
    }

    @Test fun `a panel registered by both resolves to exactly one device, the minted identity first`() {
        val devices = listOf(mqttDevice("mqtt-device", "ha-paneld-uid-abc"), paDevice("pa-device", entry))

        assertEquals("mqtt-device", found(devices, entries = setOf(entry)))
        assertEquals(
            listOf("mqtt-device", "pa-device"),
            HaPanelDeviceMatcher.all(devices, "abc", "panel", setOf(entry)).map { it.getString("id") },
        )
    }

    @Test fun `the proven Panel Assistant device outranks the historical panel id`() {
        val devices = listOf(mqttDevice("merged-device", "ha-paneld-panel"), paDevice("pa-device", entry))

        assertEquals("pa-device", found(devices, entries = setOf(entry)))
    }

    @Test fun `two devices in the deciding tier are ambiguous, never a guess`() {
        val devices = listOf(paDevice("pa-device", entry), paDevice("other-pa-device", otherEntry))

        assertEquals(
            HaPanelDeviceMatcher.Match.Ambiguous,
            HaPanelDeviceMatcher.preferred(devices, "abc", "panel", setOf(entry, otherEntry)),
        )
    }

    @Test fun `the retired Android id identifier is never a match, in either integration's shape`() {
        val retired = mqttDevice("merged-device", "ha-paneld-aid-$clonedAndroidId")

        assertEquals(
            HaPanelDeviceMatcher.Match.Missing,
            HaPanelDeviceMatcher.preferred(listOf(retired), clonedAndroidId, "panel", emptySet()),
        )
        assertEquals(
            "pa-device",
            found(listOf(retired, paDevice("pa-device", entry)), entries = setOf(entry), uid = clonedAndroidId),
        )
        assertEquals(
            listOf("pa-device"),
            HaPanelDeviceMatcher.all(
                listOf(retired, paDevice("pa-device", entry)), clonedAndroidId, "panel", setOf(entry),
            ).map { it.getString("id") },
        )
    }

    // --- both consumers use it ------------------------------------------------------------------

    @Test fun `presence discovery works for a panel registered only by Panel Assistant`() {
        val devices = response(paDevice("pa-device", entry, area = "study"), mqttDevice("motion-device", "motion", "study"))
        val areas = response(JSONObject().put("area_id", "study").put("name", "Study"))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(display("binary_sensor.den_proximity", "pa-device", "panel_assistant"))
            .put(display("binary_sensor.study_motion", "motion-device", "mqtt"))))
        val states = JSONArray()
            .put(state("binary_sensor.den_proximity", "on", "occupancy"))
            .put(state("binary_sensor.study_motion", "off", "motion"))

        val projection = project(devices, areas, entities, states)

        assertEquals("study", projection.panelAreaId)
        assertEquals(
            "the panel's own proximity must not become an Area source",
            listOf("binary_sensor.study_motion"),
            projection.candidates.map { it.entityId },
        )
    }

    @Test fun `a panel registered by both keeps its own entities out of the sources on both devices`() {
        val devices = response(
            mqttDevice("mqtt-device", "ha-paneld-uid-abc", "outside"),
            paDevice("pa-device", entry, area = "outside"),
            mqttDevice("motion-device", "motion", "outside"),
        )
        val areas = response(JSONObject().put("area_id", "outside").put("name", "Outside"))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(display("binary_sensor.mqtt_proximity", "mqtt-device", "mqtt"))
            .put(display("binary_sensor.native_proximity", "pa-device", "panel_assistant"))
            .put(display("binary_sensor.yard_motion", "motion-device", "mqtt"))))
        val states = JSONArray()
            .put(state("binary_sensor.mqtt_proximity", "on", "occupancy"))
            .put(state("binary_sensor.native_proximity", "on", "occupancy"))
            .put(state("binary_sensor.yard_motion", "off", "motion"))

        val projection = project(devices, areas, entities, states)

        assertEquals(listOf("binary_sensor.yard_motion"), projection.candidates.map { it.entityId })
    }

    @Test fun `the Area catalogue finds a panel registered only by Panel Assistant`() {
        val devices = response(paDevice("pa-device", entry, area = "study"))
        val areas = listOf(HaAreaProtocol.HaArea("study", "Study"))

        val area = HaAreaProtocol.panelDeviceArea(devices, areas, "abc", "panel", setOf(entry))

        assertTrue(area.found)
        assertEquals("pa-device", area.deviceId)
        assertEquals("Study", area.areaName)
        assertFalse(HaAreaProtocol.panelDeviceArea(devices, areas, "abc", "panel", emptySet()).found)
    }

    private fun found(devices: List<JSONObject>, entries: Set<String>, uid: String = "abc"): String {
        val match = HaPanelDeviceMatcher.preferred(devices, uid, "panel", entries)
        assertTrue("expected exactly one device, got $match", match is HaPanelDeviceMatcher.Match.Found)
        return (match as HaPanelDeviceMatcher.Match.Found).device.getString("id")
    }

    /** Asserts the projection succeeds, so a lost device match fails as an assertion, not an exception. */
    private fun project(devices: JSONObject, areas: JSONObject, entities: JSONObject, states: JSONArray) =
        runCatching { HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel", setOf(entry)) }
            .also { assertTrue("projection failed: ${it.exceptionOrNull()?.message}", it.isSuccess) }
            .getOrThrow()

    /** A `get_entries` answer for two Panel Assistant panels, as Core returns it. */
    private fun probe() = JSONObject().put("result", JSONObject()
        .put("sensor.study_status", entityRow("${entry}_status", entry))
        .put("update.study_panel_assistant", entityRow(entry, entry))
        .put("binary_sensor.study_proximity", entityRow("${did}_proximity", entry))
        .put("light.den_backlight", entityRow("${otherDid}_backlight", otherEntry))
        .put("sensor.removed_meanwhile", JSONObject.NULL))

    private fun entityRow(uniqueId: String, entryId: String, platform: String = "panel_assistant") = JSONObject()
        .put("platform", platform).put("unique_id", uniqueId).put("config_entry_id", entryId)

    private fun display(entityId: String, deviceId: String, platform: String) = JSONObject()
        .put("ei", entityId).put("di", deviceId).put("pl", platform)

    private fun mqttDevice(id: String, identifier: String, area: String = "") = device(id, area, "mqtt", identifier)

    private fun paDevice(id: String, entryId: String, area: String = "") = device(id, area, "panel_assistant", entryId)

    private fun device(id: String, area: String, domain: String, identifier: String) = JSONObject()
        .put("id", id).put("area_id", area)
        .put("identifiers", JSONArray().put(JSONArray().put(domain).put(identifier)))

    private fun response(vararg rows: JSONObject) = JSONObject().put("result", JSONArray().apply { rows.forEach(::put) })

    private fun state(entity: String, value: String, deviceClass: String) = JSONObject()
        .put("entity_id", entity).put("state", value)
        .put("attributes", JSONObject().put("device_class", deviceClass))
}
