package io.github.maxlyth.hapaneld.sensors

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.net.URLEncoder

class HaPresenceProtocolTest {
    private val clonedAndroidId = "9f86d081884c7d65"

    @Test fun `panel Area projection needs only device and Area registries`() {
        val devices = response(JSONArray().put(device("panel-device", "office", "ha-paneld-uid-abc")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "office").put("name", "Office")))

        val area = HaPresenceProtocol.projectPanelArea(devices, areas, "abc", "legacy")

        assertEquals(HaPanelArea("office", "Office"), area)
    }

    @Test fun `a locally configured area names the room presence sources come from`() {
        // A person may deliberately point the panel at a different room than its HA device sits in: a
        // panel whose own area holds no motion entities takes its auto-sleep sources
        // from a neighbouring room. Resolution is by NAME, case-insensitively, against HA's own list.
        val devices = response(JSONArray().put(device("panel-device", "hall", "ha-paneld-uid-abc")))
        val areas = response(JSONArray()
            .put(JSONObject().put("area_id", "hall").put("name", "Hall"))
            .put(JSONObject().put("area_id", "office").put("name", "Office")))

        assertEquals(
            HaPanelArea("office", "Office"),
            HaPresenceProtocol.projectPanelArea(devices, areas, "abc", "legacy", preferredAreaName = " office "),
        )
        // Blank preference keeps the device's own registry area — the overwhelmingly common case.
        assertEquals(
            HaPanelArea("hall", "Hall"),
            HaPresenceProtocol.projectPanelArea(devices, areas, "abc", "legacy", preferredAreaName = ""),
        )
        // An unknown name (a renamed or deleted area) degrades to the registry area instead of failing.
        assertEquals(
            HaPanelArea("hall", "Hall"),
            HaPresenceProtocol.projectPanelArea(devices, areas, "abc", "legacy", preferredAreaName = "Snug"),
        )
        // And a resolvable preference works even for a device HA has not put in any area yet.
        val bareDevice = response(JSONArray().put(device("panel-device", "", "ha-paneld-uid-abc")))
        assertEquals(
            HaPanelArea("office", "Office"),
            HaPresenceProtocol.projectPanelArea(bareDevice, areas, "abc", "legacy", preferredAreaName = "Office"),
        )
    }

    @Test fun `panel Area projection reports unassigned without entity data`() {
        val devices = response(JSONArray().put(device("panel-device", "", "ha-paneld-uid-abc")))
        val areas = response(JSONArray())

        val failure = runCatching {
            HaPresenceProtocol.projectPanelArea(devices, areas, "abc", "legacy")
        }.exceptionOrNull()

        assertTrue(failure is HaProtocolException)
        assertTrue(failure?.message.orEmpty().contains("no Area"))
    }

    @Test fun `large source sets are byte batched without dropping entities`() {
        val entities = (1..2_000).mapTo(linkedSetOf()) { "binary_sensor.room_motion_$it" }
        val longEntities = (1..5_000).mapTo(linkedSetOf()) {
            "binary_sensor.${"motion_${it}_".padEnd(220, 'x')}"
        }

        val history = presenceHistoryBatches(entities)
        val subscriptions = presenceSubscriptionBatches(longEntities)

        assertTrue(history.size > 1)
        assertTrue(subscriptions.size > 1)
        assertEquals(entities, history.flatten().toSet())
        assertEquals(longEntities, subscriptions.flatten().toSet())
        assertTrue(history.all { batch ->
            URLEncoder.encode(batch.sorted().joinToString(","), Charsets.UTF_8.name())
                .toByteArray(Charsets.US_ASCII).size <= 4 * 1024
        })
        assertTrue(subscriptions.all { batch ->
            batch.sumOf { it.toByteArray(Charsets.UTF_8).size + 3 } <= 1024 * 1024
        })
    }

    @Test fun `the cloned Android id identifier does not resolve the panel device`() {
        // A merged device a cloned fleet already has still carries this identifier. Matching it is what
        // resolved several panels to one row, so the panel device lookup must ignore it.
        val devices = response(JSONArray().put(device("merged-device", "kitchen", "ha-paneld-aid-$clonedAndroidId")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))

        val failure = runCatching {
            HaPresenceProtocol.projectPanelArea(devices, areas, clonedAndroidId, "panel_one")
        }.exceptionOrNull()

        assertTrue("the retired identifier must not resolve a device", failure is HaProtocolException)
        assertTrue(failure?.message.orEmpty().contains("missing"))
    }

    @Test fun `a device carrying only the cloned Android id identifier is not owned by the panel`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-$clonedAndroidId"))
            .put(device("merged-device", "kitchen", "ha-paneld-aid-$clonedAndroidId")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.merged_motion").put("di", "merged-device").put("pl", "mqtt"))))
        val states = JSONArray().put(state("binary_sensor.merged_motion", "off", "motion"))

        val projection = HaPresenceProtocol.projectArea(devices, areas, entities, states, clonedAndroidId, "panel_one")

        assertEquals(
            "the merged device is not this panel, so its entities remain external Area motion",
            listOf("binary_sensor.merged_motion"),
            projection.candidates.map { it.entityId },
        )
    }

    @Test fun `panel-owned occupancy is excluded while external Area motion remains`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(device("motion-device", "kitchen", "motion-id")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.panel_proximity").put("di", "panel-device").put("pl", "mqtt"))
            .put(JSONObject().put("ei", "binary_sensor.kitchen_motion").put("di", "motion-device").put("pl", "mqtt"))))
        val states = JSONArray()
            .put(state("binary_sensor.panel_proximity", "on", "occupancy"))
            .put(state("binary_sensor.kitchen_motion", "off", "motion"))

        val projection = HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel")

        assertEquals(listOf("binary_sensor.kitchen_motion"), projection.candidates.map { it.entityId })
    }

    @Test fun `panel device area inherits through candidate device`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(device("motion-device", "kitchen", "motion-id")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.kitchen_motion").put("di", "motion-device").put("pl", "mqtt"))))
        val states = JSONArray().put(JSONObject()
            .put("entity_id", "binary_sensor.kitchen_motion")
            .put("state", "off")
            .put("attributes", JSONObject().put("device_class", "occupancy").put("friendly_name", "Kitchen motion")))

        val projection = HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel")

        assertEquals("kitchen", projection.panelAreaId)
        assertEquals("Kitchen", projection.panelAreaName)
        assertEquals(listOf("binary_sensor.kitchen_motion"), projection.candidates.map { it.entityId })
        assertEquals(HaPresenceValue.OFF, projection.candidates.single().value)
    }

    @Test fun `configured Area excludes every duplicate legacy panel device without guessing fallback Area`() {
        val devices = response(JSONArray()
            .put(device("legacy-panel-one", "office", "ha-paneld-panel"))
            .put(device("legacy-panel-two", "office", "ha-paneld-panel"))
            .put(device("motion-device", "office", "motion-id")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "office").put("name", "Office")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.panel_one").put("di", "legacy-panel-one").put("pl", "mqtt"))
            .put(JSONObject().put("ei", "binary_sensor.panel_two").put("di", "legacy-panel-two").put("pl", "mqtt"))
            .put(JSONObject().put("ei", "binary_sensor.office_motion").put("di", "motion-device").put("pl", "mqtt"))))
        val states = JSONArray()
            .put(state("binary_sensor.panel_one", "on", "occupancy"))
            .put(state("binary_sensor.panel_two", "on", "occupancy"))
            .put(state("binary_sensor.office_motion", "off", "motion"))

        val projection = HaPresenceProtocol.projectArea(
            devices, areas, entities, states, "abc", "panel", preferredAreaName = "Office",
        )

        assertEquals(listOf("binary_sensor.office_motion"), projection.candidates.map { it.entityId })
        val fallbackFailure = runCatching {
            HaPresenceProtocol.projectPanelArea(devices, areas, "abc", "panel")
        }.exceptionOrNull()
        assertTrue(fallbackFailure is HaProtocolException)
        assertTrue(fallbackFailure?.message.orEmpty().contains("ambiguous"))
    }

    @Test fun `malformed unrelated device rows do not abort Area projection`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(JSONObject().put("area_id", "elsewhere"))
            .put(JSONObject().put("id", "not.a.registry.id").put("area_id", "elsewhere"))
            .put(device("motion-device", "kitchen", "motion-id")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.kitchen_motion").put("di", "motion-device").put("pl", "mqtt"))))
        val states = JSONArray().put(state("binary_sensor.kitchen_motion", "off", "motion"))

        val projection = HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel")

        assertEquals(listOf("binary_sensor.kitchen_motion"), projection.candidates.map { it.entityId })
        assertEquals(HaPresenceAuthority.ASSERT_PRESENCE, projection.candidates.single().authority)
    }

    @Test fun `entity with an unprojectable device remains supporting only`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(JSONObject().put("area_id", "kitchen")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.unprojectable_motion")
                .put("di", "missing-device").put("ai", "kitchen").put("pl", "mqtt"))))
        val states = JSONArray().put(state("binary_sensor.unprojectable_motion", "on", "motion"))

        val projection = HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel")

        assertEquals(listOf("binary_sensor.unprojectable_motion"), projection.candidates.map { it.entityId })
        assertEquals(HaPresenceAuthority.SUPPORTING_ONLY, projection.candidates.single().authority)
        assertFalse(projection.candidates.any {
            it.authority == HaPresenceAuthority.ASSERT_PRESENCE && it.value == HaPresenceValue.ON
        })
    }

    @Test fun `entity area override excludes a device inherited from panel area`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(device("motion-device", "kitchen", "motion-id")))
        val areas = response(JSONArray()
            .put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen"))
            .put(JSONObject().put("area_id", "hall").put("name", "Hall")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.motion").put("di", "motion-device").put("ai", "hall").put("pl", "mqtt"))))
        val states = JSONArray().put(state("binary_sensor.motion", "on", "motion"))

        assertTrue(HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel").candidates.isEmpty())
    }

    @Test fun `unrelated binary device classes are excluded`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(device("door-device", "kitchen", "door-id")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.door").put("di", "door-device").put("pl", "mqtt"))))

        val projected = HaPresenceProtocol.projectArea(
            devices, areas, entities, JSONArray().put(state("binary_sensor.door", "on", "door")), "abc", "panel",
        )
        assertTrue(projected.candidates.isEmpty())
    }

    @Test fun `presence authority follows registry provenance rather than entity wording`() {
        val devices = response(JSONArray()
            .put(device("panel-device", "kitchen", "ha-paneld-uid-abc"))
            .put(device("motion-device", "kitchen", "motion-id")))
        val areas = response(JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen")))
        val entities = JSONObject().put("result", JSONObject().put("entities", JSONArray()
            .put(JSONObject().put("ei", "binary_sensor.kitchen_is_deserted")
                .put("di", "motion-device").put("pl", "mqtt"))
            .put(JSONObject().put("ei", "binary_sensor.derived_occupancy")
                .put("ai", "kitchen").put("pl", "bayesian"))
            .put(JSONObject().put("ei", "binary_sensor.device_attached_template")
                .put("di", "motion-device").put("pl", "template"))
            .put(JSONObject().put("ei", "binary_sensor.unknown_device_presence")
                .put("ai", "kitchen").put("di", "missing-device").put("pl", "mqtt"))
            .put(JSONObject().put("ei", "binary_sensor.unlabelled_presence")
                .put("di", "motion-device"))
            .put(JSONObject().put("ei", "binary_sensor.future_physical_presence")
                .put("di", "motion-device").put("pl", "future_physical"))))
        val states = JSONArray()
            .put(state("binary_sensor.kitchen_is_deserted", "on", "occupancy"))
            .put(state("binary_sensor.derived_occupancy", "on", "occupancy"))
            .put(state("binary_sensor.device_attached_template", "on", "occupancy"))
            .put(state("binary_sensor.unknown_device_presence", "on", "occupancy"))
            .put(state("binary_sensor.unlabelled_presence", "on", "occupancy"))
            .put(state("binary_sensor.future_physical_presence", "on", "occupancy"))

        val candidates = HaPresenceProtocol.projectArea(devices, areas, entities, states, "abc", "panel")
            .candidates.associateBy(HaPresenceCandidate::entityId)

        assertEquals(HaPresenceAuthority.ASSERT_PRESENCE,
            candidates.getValue("binary_sensor.kitchen_is_deserted").authority)
        assertEquals(HaPresenceAuthority.SUPPORTING_ONLY,
            candidates.getValue("binary_sensor.derived_occupancy").authority)
        assertEquals(HaPresenceAuthority.SUPPORTING_ONLY,
            candidates.getValue("binary_sensor.device_attached_template").authority)
        assertEquals(HaPresenceAuthority.SUPPORTING_ONLY,
            candidates.getValue("binary_sensor.unknown_device_presence").authority)
        assertEquals(HaPresenceAuthority.SUPPORTING_ONLY,
            candidates.getValue("binary_sensor.unlabelled_presence").authority)
        assertEquals(HaPresenceAuthority.ASSERT_PRESENCE,
            candidates.getValue("binary_sensor.future_physical_presence").authority)
    }

    @Test fun `fixed short episodes are inferred pulse like and seed a bounded lease`() {
        val entity = "binary_sensor.motion"
        val transitions = mutableListOf(HaPresenceTransition(entity, 0L, HaPresenceValue.OFF))
        repeat(10) { index ->
            val start = index * 20L * 60_000L + (index / 5) * 24L * 60L * 60_000L
            transitions += HaPresenceTransition(entity, start + 60_000L, HaPresenceValue.ON)
            transitions += HaPresenceTransition(entity, start + 2L * 60_000L, HaPresenceValue.OFF)
        }

        val evidence = HaPresenceProtocol.evidence(transitions.sortedBy { it.atEpochMs })

        assertEquals(HaPresenceBehaviour.PULSE_LIKE, evidence.behaviour)
        assertTrue(evidence.autoEligible)
        assertTrue(evidence.suggestedLeaseMs in MIN_AUTO_SLEEP_LEASE_MS..MAX_AUTO_SLEEP_LEASE_MS)
    }

    @Test fun `source gaps use the eightieth percentile above a ten minute floor`() {
        val entity = "binary_sensor.motion"
        val minute = 60_000L
        var cursor = 23L * 60L * minute
        val transitions = mutableListOf(HaPresenceTransition(entity, cursor, HaPresenceValue.OFF))
        val gapsMinutes = listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 20L, 30L)
        repeat(gapsMinutes.size + 1) { index ->
            transitions += HaPresenceTransition(entity, cursor, HaPresenceValue.ON)
            cursor += minute
            transitions += HaPresenceTransition(entity, cursor, HaPresenceValue.OFF)
            if (index < gapsMinutes.size) cursor += gapsMinutes[index] * minute
        }

        val evidence = HaPresenceProtocol.evidence(transitions)

        assertEquals(HaPresenceBehaviour.PULSE_LIKE, evidence.behaviour)
        assertEquals(10L * minute, MIN_AUTO_SLEEP_LEASE_MS)
        assertEquals(14L * minute, evidence.suggestedLeaseMs)
    }

    @Test fun `source gap lease uses nearest rank for sparse evidence`() {
        val entity = "binary_sensor.motion"
        val minute = 60_000L
        var cursor = 23L * 60L * minute
        val transitions = mutableListOf(HaPresenceTransition(entity, cursor, HaPresenceValue.OFF))
        val gapsMinutes = listOf(5L, 5L, 60L, 61L, 61L, 61L, 61L)
        repeat(gapsMinutes.size + 1) { index ->
            transitions += HaPresenceTransition(entity, cursor, HaPresenceValue.ON)
            cursor += minute
            transitions += HaPresenceTransition(entity, cursor, HaPresenceValue.OFF)
            if (index < gapsMinutes.size) cursor += gapsMinutes[index] * minute
        }

        val evidence = HaPresenceProtocol.evidence(transitions)

        assertEquals(HaPresenceBehaviour.PULSE_LIKE, evidence.behaviour)
        assertEquals(60L * minute, evidence.suggestedLeaseMs)
    }

    @Test fun `boundary active and unavailable intervals never become complete episodes`() {
        val entity = "binary_sensor.motion"
        val transitions = listOf(
            HaPresenceTransition(entity, 0L, HaPresenceValue.ON),
            HaPresenceTransition(entity, 5_000L, HaPresenceValue.OFF),
            HaPresenceTransition(entity, 10_000L, HaPresenceValue.ON),
            HaPresenceTransition(entity, 12_000L, HaPresenceValue.UNAVAILABLE),
            HaPresenceTransition(entity, 20_000L, HaPresenceValue.OFF),
            HaPresenceTransition(entity, 30_000L, HaPresenceValue.ON),
            HaPresenceTransition(entity, 35_000L, HaPresenceValue.OFF),
        )
        assertEquals(listOf(HaPresenceEpisode(30_000L, 35_000L)), HaPresenceProtocol.episodes(transitions))
        assertFalse(HaPresenceProtocol.evidence(transitions).autoEligible)
    }

    @Test fun `minimal history rows inherit entity id and preserve unavailable`() {
        val entity = "binary_sensor.motion"
        val start = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli()
        val response = JSONArray().put(JSONArray()
            .put(history(entity, "off", "2026-07-20T00:00:00+00:00"))
            .put(history("", "on", "2026-07-20T00:00:01.000000+00:00"))
            .put(history("", "unavailable", "2026-07-20T00:00:02+00:00")))

        val parsed = HaPresenceProtocol.parseHistory(response, setOf(entity), start, start + 3_000L).getValue(entity)

        assertEquals(listOf(HaPresenceValue.OFF, HaPresenceValue.ON, HaPresenceValue.UNAVAILABLE), parsed.map { it.value })
    }

    private fun response(result: JSONArray) = JSONObject().put("result", result)

    private fun device(id: String, area: String, identifier: String) = JSONObject()
        .put("id", id).put("area_id", area)
        .put("identifiers", JSONArray().put(JSONArray().put("mqtt").put(identifier)))

    private fun state(entity: String, value: String, deviceClass: String) = JSONObject()
        .put("entity_id", entity).put("state", value)
        .put("attributes", JSONObject().put("device_class", deviceClass).put("friendly_name", entity))

    private fun history(entity: String, value: String, epochMs: Long) = JSONObject()
        .apply { if (entity.isNotBlank()) put("entity_id", entity) }
        .put("state", value).put("last_changed", Instant.ofEpochMilli(epochMs).toString())

    private fun history(entity: String, value: String, timestamp: String) = JSONObject()
        .apply { if (entity.isNotBlank()) put("entity_id", entity) }
        .put("state", value).put("last_changed", timestamp)
}
