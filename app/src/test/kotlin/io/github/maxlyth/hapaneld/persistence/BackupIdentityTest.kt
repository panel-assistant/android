package io.github.maxlyth.hapaneld.persistence

import io.github.maxlyth.hapaneld.panelAssistantDiscoveryId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupIdentityTest {
    private val androidId = "9774d56d682e549c"
    private val discoveryId = panelAssistantDiscoveryId(androidId)!!

    private fun manifest(fragment: String) = JSONObject("{\"kind\":\"ha-paneld-backup\"$fragment}")

    @Test fun theManifestCarriesThePseudonymAndTheWritingPackage() {
        val written = manifest(BackupIdentity.manifestFragment(discoveryId, "io.github.maxlyth.hapaneld", mqttConnected = true))

        assertEquals(discoveryId, written.getString("discovery_id"))
        assertEquals("io.github.maxlyth.hapaneld", written.getString("package"))
    }

    @Test fun theRawAndroidIdIsNeverWritten() {
        val fragment = BackupIdentity.manifestFragment(discoveryId, "io.github.maxlyth.hapaneld", mqttConnected = true)

        assertFalse(fragment.contains(androidId))
        // Anything that is not the 64-hex pseudonym, the Android id included, is dropped, not written.
        assertFalse(BackupIdentity.manifestFragment(androidId, "pkg", false).contains("discovery_id"))
        assertFalse(BackupIdentity.manifestFragment(null, "pkg", false).contains("discovery_id"))
    }

    @Test fun theManifestRecordsWhetherItsWriterWasConnectedToMqtt() {
        assertTrue(BackupIdentity.writerMqttConnected(manifest(BackupIdentity.manifestFragment(discoveryId, "pkg", true))))
        assertFalse(BackupIdentity.writerMqttConnected(manifest(BackupIdentity.manifestFragment(discoveryId, "pkg", false))))
        assertFalse("an archive that predates the field claims nothing", BackupIdentity.writerMqttConnected(manifest("")))
        assertFalse(BackupIdentity.writerMqttConnected(manifest(",\"mqtt_connected\":\"true\"")))
    }

    @Test fun bothIdentitiesOfThisAppProveTheSameDevice() {
        val written = manifest(BackupIdentity.manifestFragment(discoveryId, "io.github.maxlyth.hapaneld", mqttConnected = true))

        assertTrue(BackupIdentity.sameDevice(written, panelAssistantDiscoveryId(androidId)))
    }

    @Test fun anotherDeviceIsNotTheSameDevice() {
        val written = manifest(BackupIdentity.manifestFragment(discoveryId, "io.github.maxlyth.hapaneld", mqttConnected = true))

        assertFalse(BackupIdentity.sameDevice(written, panelAssistantDiscoveryId("0000000000000001")))
    }

    @Test fun anArchiveOrADeviceWithoutAPseudonymProvesNothing() {
        val written = manifest(BackupIdentity.manifestFragment(discoveryId, "pkg", false))

        assertFalse(BackupIdentity.sameDevice(manifest(""), discoveryId))
        assertFalse(BackupIdentity.sameDevice(written, null))
        assertFalse(BackupIdentity.sameDevice(written, ""))
        assertFalse(BackupIdentity.sameDevice(manifest(",\"discovery_id\":\"\""), ""))
        assertFalse(BackupIdentity.sameDevice(manifest(",\"discovery_id\":7"), discoveryId))
        assertNull(BackupIdentity.discoveryId(manifest(",\"discovery_id\":\"${discoveryId.uppercase()}\"")))
    }

    @Test fun rawPreferencesCarryOnlyStringEntriesOfTheListedStores() {
        val fragment = RawPreferenceBackup.manifestFragment { store ->
            if (store == "proximity-wake-invalidation") mapOf("fp-b" to "tok2", "fp-a" to "tok1", "count" to 3)
            else error("unlisted store $store was read")
        }

        val section = manifest(fragment).getJSONObject("raw_preferences")
        assertEquals(setOf("proximity-wake-invalidation"), section.keys().asSequence().toSet())
        val store = section.getJSONObject("proximity-wake-invalidation")
        assertEquals(setOf("fp-a", "fp-b"), store.keys().asSequence().toSet())
        assertEquals("tok1", store.getString("fp-a"))
    }

    @Test fun anEmptyStoreWritesNoSection() {
        assertEquals("", RawPreferenceBackup.manifestFragment { emptyMap<String, Any>() })
        assertEquals(emptyMap<String, Map<String, String>>(), RawPreferenceBackup.restorable(manifest("")))
    }

    @Test fun aWrittenSectionRestoresExactly() {
        val fragment = RawPreferenceBackup.manifestFragment { mapOf("fp \"quoted\"" to "tok\n1") }

        assertEquals(
            mapOf("proximity-wake-invalidation" to mapOf("fp \"quoted\"" to "tok\n1")),
            RawPreferenceBackup.restorable(manifest(fragment)),
        )
    }

    @Test fun aSectionAWriterCouldNotHaveProducedIsMalformed() {
        fun section(body: String) = RawPreferenceBackup.restorable(manifest(",\"raw_preferences\":$body"))

        assertNull(section("[]"))
        assertNull(section("{\"ha-paneld\":{\"ui_language\":\"de\"}}"))
        assertNull(section("{\"proximity-wake-invalidation\":[]}"))
        assertNull(section("{\"proximity-wake-invalidation\":{\"k\":1}}"))
        assertNull(section("{\"proximity-wake-invalidation\":{\"\":\"v\"}}"))
        assertNull(section("{\"proximity-wake-invalidation\":{\"k\":\"${"v".repeat(1_025)}\"}}"))
        val tooMany = (0..64).joinToString(",") { "\"k$it\":\"v\"" }
        assertNull(section("{\"proximity-wake-invalidation\":{$tooMany}}"))
    }

    @Test fun everyRawPreferenceStoreInTheAppIsClassified() {
        // A store opened with getSharedPreferences lives outside app_state, so the backup cannot see it
        // unless it is listed. AppState's own calls are the legacy mirror and bridge metadata, and
        // NativeLocale reads that mirror; any other file must be a listed store.
        val sources = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("getSharedPreferences(") }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("AppState.kt", "NativeLocale.kt", "ProximityLearningRuntime.kt", "PaneldServer.kt"),
            sources,
        )
        assertEquals(setOf("proximity-wake-invalidation"), RawPreferenceBackup.STORES.keys)
    }
}
