package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.http.HaAreaProtocol.ReconcileAction
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAreaProtocolTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private fun areasJson() = JSONObject(
        """{"result":[
            {"area_id":"office","name":"Office","icon":"mdi:desk"},
            {"area_id":"kitchen","name":"Kitchen"},
            {"area_id":"","name":"Broken"},
            {"area_id":"noname","name":""}
        ]}""",
    )

    private fun devicesJson(areaId: String = "office") = JSONObject(
        """{"result":[
            {"id":"other","identifiers":[["mqtt","something-else"]],"area_id":"kitchen"},
            {"id":"dev1","identifiers":[["mqtt","ha-paneld-uid-abc123"],["mqtt","ha-paneld-alpha"]],"area_id":"$areaId"}
        ]}""",
    )

    @Test fun aPersonsChoiceIsAnOverrideAdoptionMustNotUndo() {
        // The first precedence rule reverted every deliberate divergence seconds after it was saved: on an
        // observed panel the area was set to a neighbouring room (its own HA area has no motion
        // entities, so auto-sleep needed sources from next door), the change was saved, and the value snapped
        // back. A user-chosen value beats adoption; only ADOPTED values follow Home Assistant.
        assertEquals(
            ReconcileAction.KEEP,
            HaAreaProtocol.reconcile("Office", "Hall", admin = false, userOverride = true),
        )
        assertEquals(
            "an override is kept for admins too — overriding is not the same as moving the device",
            ReconcileAction.KEEP,
            HaAreaProtocol.reconcile("Office", "Hall", admin = true, userOverride = true),
        )
        // Blank local means "follow Home Assistant" even when the override bit is somehow still set.
        assertEquals(ReconcileAction.ADOPT_HA, HaAreaProtocol.reconcile("", "Hall", admin = false, userOverride = true))
        // An override HA agrees with in a different casing is not overriding anything: adopt HA's spelling
        // (the caller clears the bit on adoption).
        assertEquals(
            ReconcileAction.ADOPT_HA,
            HaAreaProtocol.reconcile("office", "Office", admin = false, userOverride = true),
        )
        // Seeding an area-less device from a pending request is unchanged by the override bit.
        assertEquals(ReconcileAction.WRITE_BACK, HaAreaProtocol.reconcile("Office", "", admin = true, userOverride = true))
    }

    @Test fun thePrecedenceRuleIsHaWinsLocalOnlySeedsAndAdminsApply() {
        // HA reports a different area: adopt it — HA is canonical, whoever the session is.
        assertEquals(ReconcileAction.ADOPT_HA, HaAreaProtocol.reconcile("Kitchen", "Office", admin = false))
        assertEquals(ReconcileAction.ADOPT_HA, HaAreaProtocol.reconcile("", "Office", admin = false))
        // Same area, HA's casing differs: adopt HA's spelling, it is what users see everywhere else.
        assertEquals(ReconcileAction.ADOPT_HA, HaAreaProtocol.reconcile("office", "Office", admin = true))
        // HA blank + local request + admin: the pending request applies. This is also how an admin
        // signing in later completes a non-admin's earlier choice.
        assertEquals(ReconcileAction.WRITE_BACK, HaAreaProtocol.reconcile("Office", "", admin = true))
        // HA blank + local request + no admin: the request stands, recorded but unpromised.
        assertEquals(ReconcileAction.KEEP, HaAreaProtocol.reconcile("Office", "", admin = false))
        // Agreement and double-blank are quiet.
        assertEquals(ReconcileAction.KEEP, HaAreaProtocol.reconcile("Office", "Office", admin = true))
        assertEquals(ReconcileAction.KEEP, HaAreaProtocol.reconcile("", "", admin = true))
    }

    @Test fun areaCatalogCacheRequiresTheSameOwnerAndAnUnexpiredMonotonicAge() {
        assertTrue(haAreaCacheEntryUsable("owner-a|aid|panel", "owner-a|aid|panel", 100L, 150L, 100L))
        assertFalse(haAreaCacheEntryUsable("owner-a|aid|panel", "owner-b|aid|panel", 100L, 150L, 100L))
        assertFalse(haAreaCacheEntryUsable("owner-a|aid|panel", "owner-a|aid|panel", 100L, 200L, 100L))
        assertFalse(haAreaCacheEntryUsable("owner-a|aid|panel", "owner-a|aid|panel", 100L, 99L, 100L))
    }

    @Test fun areasReduceToTheFieldsThePickersNeedAndDropBrokenRows() {
        val areas = HaAreaProtocol.areas(areasJson())
        assertEquals(listOf("Office", "Kitchen"), areas.map { it.name })
        assertEquals("mdi:desk", areas[0].icon)
        assertEquals("", areas[1].icon)
        assertTrue(HaAreaProtocol.areas(null).isEmpty())
    }

    @Test fun thePanelDeviceIsFoundByItsOwnMqttIdentifiersAndJoinedToItsArea() {
        val areas = HaAreaProtocol.areas(areasJson())
        val found = HaAreaProtocol.panelDeviceArea(devicesJson(), areas, "abc123", "alpha", emptySet())
        assertTrue(found.found)
        assertEquals("dev1", found.deviceId)
        assertEquals("Office", found.areaName)
        // Legacy panel-id identifier is the fallback when the immutable one is absent.
        val legacyOnly = HaAreaProtocol.panelDeviceArea(devicesJson(), areas, "", "alpha", emptySet())
        assertTrue(legacyOnly.found)
        // No area on the device reads as blank, never as a guess.
        val bare = HaAreaProtocol.panelDeviceArea(devicesJson(areaId = ""), areas, "abc123", "alpha", emptySet())
        assertTrue(bare.found)
        assertEquals("", bare.areaName)
        // Unlike the presence path this never throws — setup must be able to say "not found" calmly.
        val missing = HaAreaProtocol.panelDeviceArea(JSONObject("""{"result":[]}"""), areas, "abc123", "x", emptySet())
        assertFalse(missing.found)
        assertFalse(HaAreaProtocol.panelDeviceArea(null, areas, "abc123", "x", emptySet()).found)
    }

    @Test fun jsonNullDeviceAreaIsUnassigned() {
        val devices = devicesJson()
        devices.getJSONArray("result").getJSONObject(1).put("area_id", JSONObject.NULL)

        val area = HaAreaProtocol.panelDeviceArea(
            devices, HaAreaProtocol.areas(areasJson()), "abc123", "alpha", emptySet(),
        )

        assertTrue(area.found)
        assertEquals("", area.areaId)
        assertEquals("", area.areaName)
        assertEquals(
            "a stored literal must be cleared rather than written back as a new HA area",
            ReconcileAction.ADOPT_HA,
            HaAreaProtocol.reconcile("null", area.areaName, admin = true),
        )
    }

    @Test fun existingAreaNamedLiteralNullIsNotAdoptedAsThePanelsArea() {
        val areas = HaAreaProtocol.areas(JSONObject("""{"result":[{"area_id":"bad-area","name":"null"}]}"""))
        val area = HaAreaProtocol.panelDeviceArea(
            devicesJson(areaId = "bad-area"), areas, "abc123", "alpha", emptySet(),
        )
        assertTrue(area.found)
        assertEquals("", area.areaName)
        assertTrue(HaAreaProtocol.hasLiteralNullAssignment(area, areas))
        assertEquals(ReconcileAction.KEEP, HaAreaProtocol.reconcile("", area.areaName, admin = true))
        val officeAreas = HaAreaProtocol.areas(areasJson())
        assertFalse(HaAreaProtocol.hasLiteralNullAssignment(
            HaAreaProtocol.panelDeviceArea(devicesJson(), officeAreas, "abc123", "alpha", emptySet()), officeAreas,
        ))
        assertFalse(HaAreaProtocol.hasLiteralNullAssignment(
            HaAreaProtocol.panelDeviceArea(devicesJson(areaId = "missing"), areas, "abc123", "alpha", emptySet()),
            areas,
        ))
    }

    @Test fun areaNamesResolveCaseInsensitively() {
        val areas = HaAreaProtocol.areas(areasJson())
        assertEquals("office", HaAreaProtocol.resolveAreaId(areas, "  oFFiCe "))
        assertEquals(null, HaAreaProtocol.resolveAreaId(areas, "Garage"))
    }

    @Test fun discoveryOnlySuggestsAnAreaWhenOneIsRequested() {
        // suggested_area applies at first registration only and must never appear as an empty string —
        // HA would create an unnamed area. Pinned at source because the device block is assembled by hand.
        val bridge = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        ).first { it.isFile }.readText()
        assertTrue(bridge.contains("config.haArea.takeIf(String::isNotBlank)"))
        assertTrue(bridge.contains("\"suggested_area\":\""))
    }

    @Test fun theCanonicalRuleHasAnOwnerThatDoesNotWaitForSomebodyToOpenAMenu() {
        // The rule "Home Assistant's area is canonical" was implemented only at read time, and every reader
        // was a UI control. So a panel nobody had opened the area dropdown on never adopted anything:
        // affected panels held a blank ha_area while their HA devices sat in real areas, and every
        // surface honestly reported "No area". Reachable-and-credentialled is the only
        // precondition — the registry read is an authenticated WebSocket call.
        assertTrue(HaAreaProtocol.canQueryUnprompted("http://ha.local:8123", credentialed = true))
        assertFalse("no endpoint means nothing to ask", HaAreaProtocol.canQueryUnprompted("", credentialed = true))
        assertFalse("no credential means the read cannot succeed", HaAreaProtocol.canQueryUnprompted("http://ha.local:8123", credentialed = false))
        assertFalse(HaAreaProtocol.canQueryUnprompted("   ", credentialed = true))
    }
}
