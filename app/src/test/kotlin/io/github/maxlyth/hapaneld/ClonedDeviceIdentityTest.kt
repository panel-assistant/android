package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.persistence.SqliteStatePreferences
import io.github.maxlyth.hapaneld.persistence.StateMutation
import io.github.maxlyth.hapaneld.persistence.StateNamespacePersistence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Two panels flashed from one factory image share the Android 8.1 SSAID seed, so they published one
 * identical `ha-paneld-aid-<ANDROID_ID>` device identifier. Home Assistant merges a device on ANY
 * matching identifier, so those panels collapsed into a single device: entities named after another
 * panel, no per-panel area, device page or device automations (#155).
 *
 * These tests hold the identity apart at each place the old value reached: what discovery publishes,
 * what a generated panel id is built from, what the registry read path matches on, and what a
 * settings archive is allowed to carry.
 */
class ClonedDeviceIdentityTest {

    private val clonedAndroidId = "9f86d081884c7d65"

    @Test fun clonedAndroidIdsHaveDistinctDiscoveryHealthAndStableInstallIdentities() {
        val firstStore = RestoredState(emptyMap())
        val secondStore = RestoredState(emptyMap())
        val first = config(firstStore)
        val second = config(secondStore)
        first.ensureDeviceUid()
        second.ensureDeviceUid()
        val firstHealth = io.github.maxlyth.hapaneld.http.panelAssistantDiscoveryHealthToken(first.deviceUid, clonedAndroidId)
        val secondHealth = io.github.maxlyth.hapaneld.http.panelAssistantDiscoveryHealthToken(second.deviceUid, clonedAndroidId)
        fun token(health: String, name: String) = health.trim().split(" ").single { it.startsWith("$name=") }.substringAfter('=')
        assertNotEquals(token(firstHealth, "did"), token(secondHealth, "did"))
        assertEquals(token(firstHealth, "legacy_did"), token(secondHealth, "legacy_did"))
        assertEquals(token(firstHealth, "did"), panelAssistantDiscoveryId(config(firstStore).deviceUid))
        assertFalse(firstHealth.contains(clonedAndroidId))
        assertTrue(io.github.maxlyth.hapaneld.http.panelAssistantDiscoveryHealthToken(first.deviceUid).contains(" did="))
    }

    @Test fun failedIdentityWriteRetriesTheSameIdentityAndThenSurvivesRestart() {
        val store = RestoredState(emptyMap())
        store.durable = false
        val panel = config(store)
        val first = "a".repeat(32)
        assertTrue(runCatching { panel.ensureDeviceUid { first } }.isFailure)
        assertEquals("", io.github.maxlyth.hapaneld.http.panelAssistantDiscoveryHealthToken(panel.deviceUid))
        assertEquals("", config(store).deviceUid)
        assertTrue(runCatching { panel.ensureDeviceUid { "b".repeat(32) } }.isFailure)
        store.durable = true
        assertEquals(first, panel.ensureDeviceUid { "c".repeat(32) })
        assertEquals(first, config(store).deviceUid)
    }

    @Test fun signedHandoverAdoptsDurablyButCannotReplaceAnotherInstallation() {
        val store = RestoredState(emptyMap())
        val panel = config(store)
        store.durable = false
        assertFalse(panel.adoptMigrationDeviceUid("a".repeat(32)))
        assertEquals("", panel.deviceUid)
        store.durable = true
        assertTrue(panel.adoptMigrationDeviceUid("a".repeat(32)))
        val reopened = config(store)
        assertEquals("a".repeat(32), reopened.ensureDeviceUid())
        assertFalse(reopened.adoptMigrationDeviceUid("b".repeat(32)))
        assertEquals("a".repeat(32), reopened.deviceUid)
    }

    // --- a cloned ANDROID_ID across two simulated panels ------------------------------------------

    @Test fun twoPanelsSharingAnAndroidIdMintDistinctIdentities() {
        val first = panel(broker = "")
        val second = panel(broker = "")

        first.ensureDeviceUid()
        second.ensureDeviceUid()

        assertTrue("first panel minted nothing", first.deviceUid.isNotBlank())
        assertTrue("second panel minted nothing", second.deviceUid.isNotBlank())
        assertNotEquals(
            "two panels off one factory image must not share a device identity",
            first.deviceUid,
            second.deviceUid,
        )
    }

    @Test fun twoPanelsSharingAnAndroidIdPublishNoIdentifierInCommon() {
        val first = mqttDeviceIdentifiers("panel_one", deviceUid = "aaaa1111", legacyAndroidId = "")
        val second = mqttDeviceIdentifiers("panel_two", deviceUid = "bbbb2222", legacyAndroidId = "")

        assertEquals(
            "a shared identifier is what merges two panels into one Home Assistant device",
            emptySet<String>(),
            first.toSet() intersect second.toSet(),
        )
    }

    @Test fun theRetiredAndroidIdIdentifierIsNeverPublishedOnceTheBridgeIsRetired() {
        val identifiers = mqttDeviceIdentifiers("panel_one", deviceUid = "aaaa1111", legacyAndroidId = "")

        assertEquals(listOf("ha-paneld-panel_one", "ha-paneld-uid-aaaa1111"), identifiers)
        assertTrue(
            "the cloned identifier must not appear",
            identifiers.none { it.startsWith("ha-paneld-aid-") },
        )
    }

    @Test fun anUnreadableIdentityIsOmittedRatherThanPublishedAsABarePrefix() {
        // "ha-paneld-uid-" would itself be identical on every panel that could not read its own.
        val identifiers = mqttDeviceIdentifiers("panel_one", deviceUid = "  ", legacyAndroidId = " ")

        assertEquals(listOf("ha-paneld-panel_one"), identifiers)
    }

    // --- an upgrade from the old identifiers -------------------------------------------------------

    @Test fun anInstallationWithABrokerPublishesTheLegacyIdentifierExactlyOnce() {
        val config = panel(broker = "mqtt://broker.example")

        config.ensureDeviceUid()

        assertTrue("an already-registered panel owes one bridging publication", config.legacyAidBridgePending)
        val firstPass = mqttDeviceIdentifiers("panel_one", config.deviceUid, clonedAndroidId)
        assertEquals(
            listOf("ha-paneld-panel_one", "ha-paneld-uid-${config.deviceUid}", "ha-paneld-aid-$clonedAndroidId"),
            firstPass,
        )

        config.retireLegacyAidBridge()

        assertFalse("the bridge must not survive its one publication", config.legacyAidBridgePending)
        val secondPass = mqttDeviceIdentifiers("panel_one", config.deviceUid, legacyAndroidId = "")
        assertTrue(
            "republishing a cloned identifier re-merges those panels as soon as the merged device is deleted",
            secondPass.none { it.startsWith("ha-paneld-aid-") },
        )
    }

    @Test fun aFreshInstallationNeverPublishesTheLegacyIdentifierAtAll() {
        val config = panel(broker = "")

        config.ensureDeviceUid()

        assertFalse(
            "a panel that never had a broker cannot be registered under the cloned identifier, " +
                "so publishing it even once would merge two freshly flashed panels",
            config.legacyAidBridgePending,
        )
    }

    @Test fun theMintedIdentityIsStableAcrossRestarts() {
        val store = RestoredState(emptyMap())
        val first = config(store)
        first.ensureDeviceUid()
        val minted = first.deviceUid

        val afterRestart = config(store)

        assertEquals("a new identity each boot would mint a new device each boot", minted, afterRestart.deviceUid)
        afterRestart.ensureDeviceUid()
        assertEquals("ensureDeviceUid must not re-mint over a persisted identity", minted, afterRestart.deviceUid)
    }

    // --- a panel_id rename -------------------------------------------------------------------------

    @Test fun aRenamedPanelStillResolvesToItsOwnDeviceByTheMintedIdentity() {
        val devices = registry(
            deviceRow("panel-device", "ha-paneld-uid-aaaa1111", "ha-paneld-panel_one"),
            deviceRow("other-device", "ha-paneld-uid-bbbb2222", "ha-paneld-panel_two"),
        )

        val area = HaAreaProtocolAccess.panelDeviceArea(devices, deviceUid = "aaaa1111", panelId = "panel_one_renamed")

        assertTrue("a rename must re-attach, not mint a duplicate", area.found)
        assertEquals("panel-device", area.deviceId)
    }

    @Test fun aRenamedPanelOnACloneDoesNotResolveToItsNeighboursDevice() {
        val devices = registry(
            deviceRow("panel-device", "ha-paneld-uid-aaaa1111", "ha-paneld-panel_one"),
            deviceRow("other-device", "ha-paneld-uid-bbbb2222", "ha-paneld-panel_two"),
        )

        val area = HaAreaProtocolAccess.panelDeviceArea(devices, deviceUid = "bbbb2222", panelId = "panel_two")

        assertEquals(
            "each panel must resolve to its own row, which is what the cloned identifier prevented",
            "other-device",
            area.deviceId,
        )
    }

    @Test fun theClonedAndroidIdIdentifierIsNotAMatchOnTheReadPath() {
        // The merged device a cloned fleet already has still carries this identifier. Matching it is
        // what resolved several panels to one row, so the read path must ignore it.
        val devices = registry(deviceRow("merged-device", "ha-paneld-aid-$clonedAndroidId"))

        val area = HaAreaProtocolAccess.panelDeviceArea(devices, deviceUid = clonedAndroidId, panelId = "panel_one")

        assertFalse("the retired identifier must not resolve a device", area.found)
    }

    // --- export and restore ------------------------------------------------------------------------

    @Test fun theMintedIdentityIsNotAnExportableSetting() {
        assertEquals(
            "a registered spec is exported, and an exported identity is a cloned identity",
            null,
            SettingsRegistry.spec(Config.DEVICE_UID_PREF),
        )
        assertTrue(
            "no registered setting may carry the device identity",
            SettingsRegistry.SPECS.none { it.key.contains("device_uid") },
        )
    }

    @Test fun anArchiveCarryingTheMintedIdentityIsRefusedRatherThanApplied() {
        val decision = PaneldServerAccess.planRestoreSettings(
            mapOf(Config.DEVICE_UID_PREF to "aaaa1111"),
            configuredOrigin = null,
        )

        assertEquals("a restore must never write a device identity", emptyMap<String, String>(), decision.accepted)
        assertTrue("and must say why", decision.errors.any { it.contains(Config.DEVICE_UID_PREF) })
    }

    @Test fun aRestoreOntoASecondPanelCannotCloneTheFirstPanelsIdentity() {
        val source = panel(broker = "")
        source.ensureDeviceUid()

        // Everything an archive is allowed to carry, applied to a second panel.
        val target = panel(broker = "")
        target.ensureDeviceUid()
        val restorable = PaneldServerAccess.planRestoreSettings(
            mapOf(Config.DEVICE_UID_PREF to source.deviceUid),
            configuredOrigin = null,
        ).accepted
        restorable.forEach { (key, value) -> error("unexpectedly restorable: $key=$value") }

        assertNotEquals(
            "restoring one panel's archive onto another must not clone its identity",
            source.deviceUid,
            target.deviceUid,
        )
    }

    // --- the generated panel id --------------------------------------------------------------------

    @Test fun twoFreshPanelsOffOneImageDoNotGenerateOneIdenticalPanelId() {
        val first = panelIdSuffix(clonedAndroidId, deviceUid = "aaaa1111", hasConfiguredBroker = false)
        val second = panelIdSuffix(clonedAndroidId, deviceUid = "bbbb2222", hasConfiguredBroker = false)

        assertNotEquals("identical panel ids mean identical MQTT topics, not merely a merged device", first, second)
        assertEquals("1111", first)
        assertEquals("2222", second)
    }

    @Test fun anAlreadyPublishingPanelKeepsItsHistoricalSuffix() {
        val suffix = panelIdSuffix(clonedAndroidId, deviceUid = "aaaa1111", hasConfiguredBroker = true)

        assertEquals(
            "changing this would rename every entity the panel has already published",
            clonedAndroidId.takeLast(4),
            suffix,
        )
    }

    @Test fun aPanelThatCanReadNeitherValueStillGetsAWellFormedSuffix() {
        assertEquals("panel", panelIdSuffix(androidId = "", deviceUid = "", hasConfiguredBroker = false))
        assertEquals("panel", panelIdSuffix(androidId = "", deviceUid = "", hasConfiguredBroker = true))
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun panel(broker: String): Config =
        config(RestoredState(if (broker.isBlank()) emptyMap() else mapOf("mqtt_broker" to broker)))

    private fun config(store: RestoredState): Config =
        Config(SqliteStatePreferences(store, writer))

    private fun registry(vararg rows: JSONObject): JSONObject =
        JSONObject().put("result", JSONArray().also { array -> rows.forEach(array::put) })

    private fun deviceRow(id: String, vararg identifiers: String): JSONObject {
        val tuples = JSONArray()
        identifiers.forEach { tuples.put(JSONArray().put("mqtt").put(it)) }
        return JSONObject().put("id", id).put("area_id", "").put("identifiers", tuples)
    }

    private class RestoredState(initial: Map<String, Any>) : StateNamespacePersistence {
        private val values = initial.toMutableMap()

        override fun initialize(): Map<String, Any> = values.toMap()

        var durable = true

        override fun persist(mutation: StateMutation): Boolean {
            if (!durable) return false
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            if (!durable) return false
            values.clear()
            values.putAll(snapshot)
            return true
        }
    }

    private companion object {
        private val writer = Executors.newSingleThreadExecutor()
    }
}

/** Keeps the protocol call in one place so the identifier precedence is asserted, not re-implemented. */
private object HaAreaProtocolAccess {
    fun panelDeviceArea(
        deviceResponse: JSONObject,
        deviceUid: String,
        panelId: String,
    ) = io.github.maxlyth.hapaneld.http.HaAreaProtocol.panelDeviceArea(
        deviceResponse,
        areas = emptyList(),
        deviceUid = deviceUid,
        panelId = panelId,
        panelAssistantEntryIds = emptySet(),
    )
}

private object PaneldServerAccess {
    fun planRestoreSettings(migrated: Map<String, String>, configuredOrigin: String?) =
        io.github.maxlyth.hapaneld.http.planRestoreSettings(migrated, configuredOrigin)
}
