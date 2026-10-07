package io.panelassistant.android

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.storage.StorageAutoVacuumMode
import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageHealthObservation
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthRuntimeSurfaceTest {
    @Test fun dailyObservationQualityRetriesFailedAndIncompleteEvidence() {
        assertEquals(
            StorageHealthObservationQuality.COMPLETE,
            storageHealthObservationQuality(observation()),
        )
        assertEquals(
            StorageHealthObservationQuality.FAILED,
            storageHealthObservationQuality(observation().copy(quickCheck = StorageQuickCheck.FAILED)),
        )
        assertFalse(storageHealthObservationNeedsRetry(observation()))
        assertFalse(storageHealthObservationNeedsRetry(observation().copy(quickCheck = StorageQuickCheck.FAILED)))
        val incomplete = listOf(
            observation().copy(checkedAtMillis = 0L),
            observation().copy(totalBytes = 0L, usableBytes = 0L),
            observation().copy(mainDatabaseBytes = 0L),
            observation().copy(pageSizeBytes = 0L),
            observation().copy(pageCount = 0L, freelistCount = 0L),
            observation().copy(schemaVersion = 0),
            observation().copy(quickCheck = StorageQuickCheck.NOT_RUN),
        )
        incomplete.forEach {
            assertEquals(StorageHealthObservationQuality.INCOMPLETE, storageHealthObservationQuality(it))
            assertTrue(storageHealthObservationNeedsRetry(it))
        }
    }

    @Test fun promptRecoveryOnlyCompletesForFinishedOrStoppedObservations() {
        assertTrue(storageHealthRecoveryAttemptComplete(StorageHealthObservationAttempt.Complete))
        assertTrue(storageHealthRecoveryAttemptComplete(StorageHealthObservationAttempt.Stopped))
        assertFalse(storageHealthRecoveryAttemptComplete(StorageHealthObservationAttempt.Retry))
    }

    @Test fun notificationPolicyKeepsUncheckedCancelsNonCriticalAndShowsActionableFaults() {
        assertEquals(
            StorageHealthNotificationDecision.KeepExisting,
            storageHealthNotificationDecision(StorageHealthSnapshot.UNCHECKED),
        )
        for (severity in listOf(StorageHealthSeverity.HEALTHY, StorageHealthSeverity.WARNING)) {
            assertEquals(
                StorageHealthNotificationDecision.Cancel,
                storageHealthNotificationDecision(snapshot(severity)),
            )
        }

        val critical = storageHealthNotificationDecision(
            snapshot(StorageHealthSeverity.CRITICAL, usableBytes = 42L * 1024L * 1024L),
        ) as StorageHealthNotificationDecision.Show
        assertEquals("Panel storage/database pressure critical", critical.title)
        assertTrue(critical.body.contains("42 MiB filesystem free"))
        assertTrue(critical.body.contains("WAL growth"))
        val unknownCapacity = storageHealthNotificationDecision(
            snapshot(StorageHealthSeverity.CRITICAL).copy(usableBytes = 0L, totalBytes = 0L, usedPercent = null),
        ) as StorageHealthNotificationDecision.Show
        assertFalse(unknownCapacity.body.contains("MiB filesystem free"))
        assertTrue(unknownCapacity.body.contains("WAL growth"))

        for (kind in StorageDatabaseFailureKind.entries) {
            val decision = storageHealthNotificationDecision(
                snapshot(StorageHealthSeverity.DATABASE_FAILURE, failureKind = kind)
                    .copy(databaseFailureOperation = "/data/user/0/private.db write"),
            ) as StorageHealthNotificationDecision.Show
            assertEquals("Panel database needs attention", decision.title)
            assertTrue(decision.body.contains("ha-paneld"))
            assertFalse(decision.body.contains("/data/"))
            assertFalse(decision.body.contains("private.db"))
        }
    }

    @Test fun mqttAttributesAreTypedSanitizedAndPreserveUnderlyingPressure() {
        val payload = storageHealthMqttAttributes(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                pressure = StorageHealthSeverity.CRITICAL,
                usableBytes = 123L,
                failureKind = StorageDatabaseFailureKind.STORAGE_FULL,
            ).copy(databaseFailureOperation = "/data/private.db insert secret"),
        )
        val json = JSONObject(payload)

        assertEquals("critical", json.getString("storage_pressure"))
        assertEquals("storage_full", json.getString("failure_category"))
        assertEquals(123L, json.getLong("usable_bytes"))
        assertEquals(1_000L, json.getLong("total_bytes"))
        assertEquals(877L, json.getLong("database_files_bytes"))
        assertTrue(json.get("used_percent") is Number)
        assertTrue(json.get("page_count") is Number)
        assertFalse(payload.contains("/data/"))
        assertFalse(payload.contains("secret"))
        assertFalse(payload.contains("databaseFailureOperation"))
    }

    @Test fun uncheckedMqttAttributesUseExplicitNullsAndValidVocabulary() {
        val json = JSONObject(storageHealthMqttAttributes(StorageHealthSnapshot.UNCHECKED))
        assertEquals("unchecked", json.getString("storage_pressure"))
        assertTrue(json.isNull("failure_category"))
        assertTrue(json.isNull("usable_bytes"))
        assertTrue(json.isNull("total_bytes"))
        assertTrue(json.isNull("main_database_bytes"))
        assertTrue(json.isNull("checked_at_epoch_seconds"))
        assertEquals("not_run", json.getString("quick_check"))

        val sqliteUnknown = JSONObject(
            storageHealthMqttAttributes(
                snapshot(StorageHealthSeverity.HEALTHY).copy(pageCount = 0L, freelistCount = 0L),
            ),
        )
        assertTrue(sqliteUnknown.isNull("page_size_bytes"))
        assertTrue(sqliteUnknown.isNull("page_count"))
        assertTrue(sqliteUnknown.isNull("freelist_count"))
        assertTrue(sqliteUnknown.isNull("schema_version"))
        assertEquals(
            setOf("unchecked", "healthy", "warning", "critical", "database_failure"),
            StorageHealthSeverity.entries.mapTo(linkedSetOf()) { it.name.lowercase() },
        )
    }

    @Test fun completedUnknownCapacityRetainsDatabaseEvidenceInMqttAttributes() {
        val json = JSONObject(
            storageHealthMqttAttributes(
                snapshot(StorageHealthSeverity.UNCHECKED).copy(
                    pressureSeverity = StorageHealthSeverity.UNCHECKED,
                    usableBytes = 0L,
                    totalBytes = 0L,
                    usedPercent = null,
                ),
            ),
        )
        assertTrue(json.isNull("usable_bytes"))
        assertTrue(json.isNull("total_bytes"))
        assertTrue(json.isNull("used_percent"))
        assertEquals(800L, json.getLong("main_database_bytes"))
        assertEquals(44L, json.getLong("wal_bytes"))
        assertEquals(4_096L, json.getLong("page_size_bytes"))
        assertEquals(100L, json.getLong("page_count"))
        assertEquals(14, json.getInt("schema_version"))
        assertEquals("ok", json.getString("quick_check"))
        assertEquals(1_700_000_000L, json.getLong("checked_at_epoch_seconds"))
    }

    @Test fun discoveryUsesTheSharedAuthority() {
        val panel = "test"
        val topic = "homeassistant/sensor/${panel}_storage_health/config"
        assertTrue(topic in mqttKnownConfigTopics(panel))
        assertTrue(
            mqttStalePanelCleanup("old", panel).any {
                it.topic == "homeassistant/sensor/old_storage_health/config" &&
                    it.payload.isEmpty() && it.retain
            },
        )

        val mqtt = source("MqttBridge.kt")
        assertTrue(mqtt.contains("\"sensor\", \"\${panel}_storage_health\""))
        assertTrue(mqtt.contains("\"entity_category\":\"diagnostic\""))
        assertTrue(mqtt.contains("stateConverger.reconcile(\"storage_health\", force = true)"))
    }

    @Test fun theMqttPayloadNamesTheFailingOperationAndTheReclamationMode() {
        val json = JSONObject(
            storageHealthMqttAttributes(
                snapshot(
                    severity = StorageHealthSeverity.DATABASE_FAILURE,
                    failureKind = StorageDatabaseFailureKind.UNKNOWN,
                ).copy(
                    databaseFailureOperation = "catalog-maintenance",
                    autoVacuumMode = StorageAutoVacuumMode.INCREMENTAL,
                ),
            ),
        )

        // `failure_category=unknown` on its own is what a report used to carry: an outcome with no
        // subject. The operation is the half that makes it reproducible.
        assertEquals("unknown", json.getString("failure_category"))
        assertFalse("the payload must carry the operation, not an explicit null", json.isNull("failure_operation"))
        assertEquals("catalog-maintenance", json.optString("failure_operation"))
        assertEquals("incremental", json.optString("auto_vacuum"))
    }

    @Test fun theMqttPayloadUsesAnExplicitNullWhenNoOperationWasRecorded() {
        val json = JSONObject(storageHealthMqttAttributes(StorageHealthSnapshot.UNCHECKED))

        assertTrue(json.isNull("failure_operation"))
        assertEquals("unknown", json.getString("auto_vacuum"))
    }

    @Test fun theNotificationNamesTheFailingOperation() {
        val decision = storageHealthNotificationDecision(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                failureKind = StorageDatabaseFailureKind.UNKNOWN,
            ).copy(databaseFailureOperation = "catalog-maintenance"),
        ) as StorageHealthNotificationDecision.Show

        assertTrue(decision.body.contains("during catalog-maintenance"))
    }

    @Test fun theNotificationOmitsTheClauseWhenNoOperationWasRecorded() {
        val decision = storageHealthNotificationDecision(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                failureKind = StorageDatabaseFailureKind.UNKNOWN,
            ),
        ) as StorageHealthNotificationDecision.Show

        assertFalse("an absent operation must not leave a dangling clause", decision.body.contains("during"))
    }

    private fun snapshot(
        severity: StorageHealthSeverity,
        pressure: StorageHealthSeverity = severity,
        usableBytes: Long = 900L,
        failureKind: StorageDatabaseFailureKind? = null,
    ): StorageHealthSnapshot = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = pressure,
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = usableBytes,
        totalBytes = 1_000L,
        usedPercent = (1_000L - usableBytes).toDouble() / 10.0,
        mainDatabaseBytes = 800L,
        walBytes = 44L,
        sidecarBytes = 33L,
        pageSizeBytes = 4_096L,
        pageCount = 100L,
        freelistCount = 4L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
        databaseFailureKind = failureKind,
    )

    private fun observation(): StorageHealthObservation = StorageHealthObservation(
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 900L,
        totalBytes = 1_000L,
        mainDatabaseBytes = 800L,
        walBytes = 44L,
        sidecarBytes = 33L,
        pageSizeBytes = 4_096L,
        pageCount = 100L,
        freelistCount = 4L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
    )

    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private fun source(name: String): String {
        return TestSources.appFile("src/main/kotlin/io/panelassistant/android/$name").readText()
    }
}
