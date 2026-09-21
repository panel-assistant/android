package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.http.HaAreaProtocol.ReconcileAction
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAreaProtocolTest {
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
            {"id":"dev1","identifiers":[["mqtt","ha-paneld-uid-abc123"],["mqtt","ha-paneld-office_panel"]],"area_id":"$areaId"}
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

    @Test fun theOverrideLifecycleIsWiredWhereTheUserSavesAndWhereAdoptionRuns() {
        // Source-pinned: the bit is only meaningful if every writer agrees on it. A save records who chose
        // the value (blank hands it back to adoption) and pokes auto-sleep so the room change acts now;
        // adoption and exact agreement both retire the bit.
        val server = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        assertFalse(server.contains("config.haAreaUserOverride = config.haArea.isNotBlank()"))
        assertTrue(server.contains("config.haAreaUserOverride == requested.isNotBlank()"))
        assertTrue(server.contains("if (requestedHaArea != null && durableHaArea == null)"))
        assertTrue(server.contains("if (nothingSaved) \"commit-failed\" else \"saved-partial\""))
        assertTrue(server.contains("config.commitHaArea(catalog.device.areaName, userOverride = false)"))
        val config = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt"),
        ).first { it.isFile }.readText()
        assertTrue(config.contains("\"ha_area\" -> {"))
        assertTrue(config.contains("putBoolean(HA_AREA_USER_OVERRIDE_PREF, value.isNotBlank())"))
        assertTrue(server.contains("autoSleepHttpApi.noteAreaChanged()"))
        assertFalse(
            "noteAreaChanged must remain abstract rather than silently defaulting to a no-op",
            server.contains("    fun noteAreaChanged() {}"),
        )
        assertTrue(
            "adoption retires the bit in the same transaction as the Area value",
            server.contains("config.commitHaArea(snapshot.localArea, userOverride = false)"),
        )
        assertTrue("the fence must include the bit", server.contains("snapshot.userOverride == config.haAreaUserOverride"))
        // Wherever the value is displayed at rest it must disclose the override — the Dashboard tab's
        // Behaviour card row carries the suffix so the state is visible without opening Configure.
        assertTrue(
            "the Behaviour row must disclose the override only while the bit is set",
            server.contains("if (key == \"ha_area\" && config.haAreaUserOverride) {"),
        )
        val areaOverrideFormatter = server.substring(
            server.indexOf("if (key == \"ha_area\" && config.haAreaUserOverride) {"),
            server.indexOf("} else null", server.indexOf("if (key == \"ha_area\" && config.haAreaUserOverride) {")),
        )
        assertTrue(
            "the localized suffix must be built through the guarded formatter constructor, which refuses " +
                "a spec whose row could never render it",
            areaOverrideFormatter.contains("SettingRowFormatter.of(key) { raw ->") &&
                areaOverrideFormatter.contains(
                    """formattedString(strings, "dashboard.value.local_override", "value" to raw)""",
                ),
        )
        assertTrue(
            "override retirement must serialize ownership revalidation with configuration mutation",
            server.contains("if (!ownsHaAreaSnapshot(snapshot)) return@synchronizedTransaction false"),
        )
        val service = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
        ).first { it.isFile }.readText()
        assertTrue(
            "an area save must refresh the running auto-sleep controller without a service restart",
            service.contains("override fun noteAreaChanged() {\n                    refreshAutoSleepPresence()"),
        )
        val refreshPresence = service.substringAfter("private fun refreshAutoSleepPresence(): Boolean {")
            .substringBefore("\n    }")
        assertTrue("the shared presence refresh must still refresh the controller",
            refreshPresence.contains("val accepted = autoSleep.refresh()"))
        assertTrue("accepted refresh must restore only trustworthy current proximity state",
            refreshPresence.contains("if (accepted && ::sensors.isInitialized)") &&
                refreshPresence.contains("autoSleep.noteProximityState(") &&
                refreshPresence.contains("sensors.proximityPresenceNear().takeIf { sensors.proximityPresenceReady() }"))
        val manager = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/sensors/HaPresenceSourceManager.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/sensors/HaPresenceSourceManager.kt"),
        ).first { it.isFile }.readText()
        assertEquals(
            "both no-source verdicts must carry the room actually searched",
            2,
            Regex(
                "NO_CREDIBLE_SOURCES,\\n\\s+\\\"No [a-z-]+ activity source is ready\\\",\\n\\s+areaName = area\\.panelAreaName,",
            ).findAll(manager).count(),
        )
        // And the runtime actually consumes the area: the controller hands it to presence discovery.
        val controller = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/control/AutoSleepController.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/control/AutoSleepController.kt"),
        ).first { it.isFile }.readText()
        assertTrue(controller.contains("preferredAreaName = next.value.haArea"))
        assertTrue(controller.contains("manager.prerequisite(current.deviceUid, current.panelId, current.haArea)"))
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
        val found = HaAreaProtocol.panelDeviceArea(devicesJson(), areas, "abc123", "office_panel")
        assertTrue(found.found)
        assertEquals("dev1", found.deviceId)
        assertEquals("Office", found.areaName)
        // Legacy panel-id identifier is the fallback when the immutable one is absent.
        val legacyOnly = HaAreaProtocol.panelDeviceArea(devicesJson(), areas, "", "office_panel")
        assertTrue(legacyOnly.found)
        // No area on the device reads as blank, never as a guess.
        val bare = HaAreaProtocol.panelDeviceArea(devicesJson(areaId = ""), areas, "abc123", "office_panel")
        assertTrue(bare.found)
        assertEquals("", bare.areaName)
        // Unlike the presence path this never throws — setup must be able to say "not found" calmly.
        val missing = HaAreaProtocol.panelDeviceArea(JSONObject("""{"result":[]}"""), areas, "abc123", "x")
        assertFalse(missing.found)
        assertFalse(HaAreaProtocol.panelDeviceArea(null, areas, "abc123", "x").found)
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
        // surface honestly reported "No area" (2026-07-26). Reachable-and-credentialled is the only
        // precondition — the registry read is an authenticated WebSocket call.
        assertTrue(HaAreaProtocol.canQueryUnprompted("http://ha.local:8123", credentialed = true))
        assertFalse("no endpoint means nothing to ask", HaAreaProtocol.canQueryUnprompted("", credentialed = true))
        assertFalse("no credential means the read cannot succeed", HaAreaProtocol.canQueryUnprompted("http://ha.local:8123", credentialed = false))
        assertFalse(HaAreaProtocol.canQueryUnprompted("   ", credentialed = true))
    }

    @Test fun theUnpromptedConvergenceIsWiredIntoTheServerLifecycle() {
        // Source-pinned because the defect was an ABSENT owner, which no pure function can detect: the rule
        // and its endpoint were both correct in isolation.
        val server = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        assertTrue("the convergence must start with the server", server.contains("startHaAreaConvergence()"))
        assertTrue(
            "the HTTP bind must succeed before convergence starts",
            server.indexOf("startOwnedHttpServer(") < server.indexOf("startHaAreaConvergence()"),
        )
        assertTrue(
            "and must be gated on HA being reachable and credentialled",
            server.contains("HaAreaProtocol.canQueryUnprompted("),
        )
        assertTrue("and must not outlive it", server.contains("haAreaJob?.cancel()"))
        // The registry, the device row and the admin flag change about once in a panel's life, so repeated
        // page paints must not each open a Home Assistant session — but the unprompted pass MUST read fresh,
        // since noticing an admin's change in HA is the only reason it exists, and a local area change must
        // invalidate rather than be answered from a catalog read before it.
        assertTrue(server.contains("private suspend fun haAreaCatalogFor("))
        assertTrue(server.contains("@Volatile private var haAreaCatalogCache: HaAreaCatalogCacheEntry?"))
        assertTrue(
            "the endpoint reads through the cache and returns reconciled truth",
            server.contains("val catalog = applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot))"),
        )
        assertTrue(
            "the convergence pass bypasses the cache",
            server.contains("applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot, fresh = true))"),
        )
        assertTrue("a local change invalidates it", server.contains("invalidateHaAreaCatalogCache()"))
        assertTrue(
            "only a successful current-owner query may be cached — failed or stale reads are never authoritative",
            server.contains(
                "if (catalog.queried && catalog.ownerKey == snapshot.ownerKey && ownsHaAreaSnapshot(snapshot))",
            ),
        )
        assertTrue("the endpoint returns post-reconciliation truth", server.contains("val catalog = applyHaAreaPrecedence("))
        assertTrue(
            "the endpoint and the loop must share one implementation of the rule",
            server.contains("private suspend fun applyHaAreaPrecedence("),
        )
        assertTrue(server.contains("haAreaWriteJob?.cancel()"))
    }

    @Test fun theAreaEndpointReconcilesAndTheConfigLaneWritesBackInTheBackground() {
        // The precedence rule must be wired at BOTH observation points: the read endpoint and the
        // post-commit config lane. Source-pinned so a refactor cannot silently drop one side.
        val server = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        assertTrue(server.contains("get(\"/config/ha-area\")"))
        assertTrue(server.contains("entityLearning.haAreaCatalog(snapshot.deviceUid, snapshot.panelId)"))
        assertTrue(server.contains("captureHaAreaSnapshot()"))
        assertTrue(server.contains("catalog.ownerKey != snapshot.ownerKey"))
        assertTrue(server.contains("synchronized(directConfigMutationLock)"))
        assertTrue(server.contains("firstOrNull { it.first == \"ha_area\" }"))
    }

    @Test fun staleCatalogsAndWritesCarryTheCredentialOwnerThatProducedThem() {
        val manager = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
        ).first { it.isFile }.readText()
        assertTrue(manager.contains("val ownerKey: String = \"\""))
        assertTrue(manager.contains("ownerKey = credentialFingerprint()"))
        assertTrue(manager.contains("expectedOwnerKey != null && credentialFingerprint() != expectedOwnerKey"))
    }
}
