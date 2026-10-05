package io.panelassistant.android.http

import io.panelassistant.android.storage.RetentionResult
import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageRemediationSummary
import io.panelassistant.android.storage.StorageRemediationVerdict
import io.panelassistant.android.storage.VacuumOutcome
import io.panelassistant.android.storage.VacuumRefusal
import io.panelassistant.android.storage.VacuumResult
import io.panelassistant.android.storage.WalCheckpointResult
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthHttpProjectionTest {
    @Test fun refreshStatusPerformsFreshStorageObservationBeforeRenderingProof() = runBlocking {
        val events = mutableListOf<String>()
        val fresh = snapshot(StorageHealthSeverity.HEALTHY)

        val result = refreshedStatusStorage(
            refreshRequested = true,
            refreshUpdates = { events += "updates" },
            refreshStorage = { events += "storage"; fresh },
            cachedStorage = { error("refresh must not reuse cached storage") },
        )
        events += "status-json"

        assertEquals(listOf("updates", "storage", "status-json"), events)
        assertEquals(fresh, result.snapshot)
        assertTrue(result.fresh)
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            databaseObservationProof(true, "0123456789abcdef0123456789abcdef", result),
        )
    }

    @Test fun incompleteRefreshCannotEchoNonceOrReusePreviouslyHealthyProof() = runBlocking {
        val cachedHealthy = snapshot(StorageHealthSeverity.HEALTHY)
        val result = refreshedStatusStorage(
            refreshRequested = true,
            refreshUpdates = {},
            refreshStorage = { null },
            cachedStorage = { cachedHealthy },
        )

        assertEquals(StorageHealthSnapshot.UNCHECKED, result.snapshot)
        assertFalse(result.fresh)
        assertNull(databaseObservationProof(true, "0123456789abcdef0123456789abcdef", result))
        assertNull(databaseObservationProof(true, "ABCDEF0123456789ABCDEF0123456789", result.copy(fresh = true)))
    }

    @Test fun uncheckedIsExplicitWithoutInventingMetricsOrWarning() {
        val projection = HealthAudit.storage(StorageHealthSnapshot.UNCHECKED)
        val json = JSONObject(projection.statusJson())

        assertTrue(projection.statusJson().startsWith("{\"state\":\"unchecked\""))
        assertEquals("unchecked", json.getString("state"))
        assertEquals("unchecked", json.getString("pressure_state"))
        assertTrue(json.isNull("usable_bytes"))
        assertTrue(json.isNull("used_percent"))
        assertTrue(json.isNull("checked_at"))
        assertFalse(json.has("failure"))
        assertNull(projection.warningHtml())
        assertEquals("", projection.bannerHtml())
        assertTrue(projection.diagnosticLine().contains("state=unchecked pressure_state=unchecked usable_bytes=unknown"))
    }

    @Test fun warningCarriesStableMachineMetricsAndActionableExistingStyleBanner() {
        val projection = HealthAudit.storage(snapshot(StorageHealthSeverity.WARNING))
        val json = JSONObject(projection.statusJson())

        assertEquals("warning", json.getString("state"))
        assertEquals("warning", json.getString("pressure_state"))
        assertEquals(300L * MIB, json.getLong("usable_bytes"))
        assertEquals(4L * GIB, json.getLong("total_bytes"))
        assertEquals(92.7, json.getDouble("used_percent"), 0.001)
        assertEquals(20L * MIB, json.getLong("database_bytes"))
        assertEquals(3L * MIB, json.getLong("wal_bytes"))
        assertEquals(64L * 1024L, json.getLong("sidecar_bytes"))
        assertEquals(4_096L, json.getLong("page_size_bytes"))
        assertEquals(1_234L, json.getLong("page_count"))
        assertEquals(45L, json.getLong("freelist_count"))
        assertEquals(11, json.getInt("schema_version"))
        assertEquals("ok", json.getString("quick_check"))
        assertEquals(1_700_000_000_000L, json.getLong("checked_at"))
        assertFalse(json.has("failure"))
        assertTrue(json.getString("summary").contains("300.0 MiB"))
        assertTrue(json.getString("action").startsWith("Review free space and WAL/database-file growth"))
        assertTrue(projection.bannerHtml().startsWith("<div class=\"setup\">"))
        assertTrue(projection.bannerHtml().contains("Storage pressure: warning"))
        assertFalse(projection.bannerHtml().contains(" crit"))
    }

    private fun remediation(verdict: StorageRemediationVerdict) = StorageRemediationSummary(
        ranAtMillis = 1_700_000_100_000L,
        verdict = verdict,
        filesDeleted = 3,
        fileBytesFreed = 5L * MIB,
        databaseBytesFreed = MIB,
        retention = RetentionResult.PRUNED,
        walCheckpoint = WalCheckpointResult.DEFERRED_BUSY,
        vacuum = VacuumOutcome(VacuumResult.REFUSED, VacuumRefusal.INCREMENTAL_AVAILABLE),
        finalPressure = StorageHealthSeverity.WARNING,
    )

    @Test fun noRemediationFieldsUntilARunHappened() {
        val projection = HealthAudit.storage(snapshot(StorageHealthSeverity.WARNING))
        val json = JSONObject(projection.statusJson())
        assertFalse(json.has("remediation"))
        assertFalse(json.has("remediation_freed_bytes"))
        assertTrue(projection.diagnosticLine().contains(" remediation=not_run"))
        assertFalse(projection.diagnosticLine().contains("remediation_at"))
    }

    @Test fun anExhaustedRemediationIsReportedAsItHappenedAndEscalatesToTheOperator() {
        val projection = HealthAudit.storage(
            snapshot(StorageHealthSeverity.WARNING).copy(remediation = remediation(StorageRemediationVerdict.EXHAUSTED)),
        )
        val json = JSONObject(projection.statusJson())

        assertEquals("exhausted", json.getString("remediation"))
        assertEquals(1_700_000_100_000L, json.getLong("remediation_at"))
        assertEquals(6L * MIB, json.getLong("remediation_freed_bytes"))
        assertEquals("pruned", json.getString("remediation_retention"))
        assertEquals("deferred_busy", json.getString("remediation_wal_checkpoint"))
        assertEquals("refused", json.getString("remediation_vacuum"))
        assertEquals("incremental_available", json.getString("remediation_vacuum_refusal"))
        assertEquals("warning", json.getString("state"))
        assertTrue(
            "the operator is told automatic cleanup is spent",
            json.getString("action").startsWith("Automatic cleanup has already removed everything it can safely remove"),
        )
        assertTrue(projection.bannerHtml().contains("Automatic cleanup has already removed everything"))
        assertTrue(projection.diagnosticLine().contains(
            "remediation=exhausted remediation_at=1700000100000 remediation_freed_bytes=6291456 " +
                "remediation_retention=pruned remediation_wal_checkpoint=deferred_busy " +
                "remediation_vacuum=refused remediation_vacuum_refusal=incremental_available",
        ))
    }

    @Test fun aRemediationThatIsNotExhaustedDoesNotEscalate() {
        listOf(StorageRemediationVerdict.DEFERRED, StorageRemediationVerdict.UNVERIFIED, StorageRemediationVerdict.RELIEVED)
            .forEach { verdict ->
                val json = JSONObject(
                    HealthAudit.storage(
                        snapshot(StorageHealthSeverity.WARNING).copy(remediation = remediation(verdict)),
                    ).statusJson(),
                )
                assertEquals(verdict.name.lowercase(), json.getString("remediation"))
                assertTrue(json.getString("action").startsWith("Review free space and WAL/database-file growth"))
            }
    }

    @Test fun anOldExhaustedRunDoesNotEscalateOnceStorageIsHealthy() {
        val json = JSONObject(
            HealthAudit.storage(
                snapshot(StorageHealthSeverity.HEALTHY).copy(remediation = remediation(StorageRemediationVerdict.EXHAUSTED)),
            ).statusJson(),
        )
        assertEquals("exhausted", json.getString("remediation"))
        assertEquals("No action needed.", json.getString("action"))
    }

    @Test fun criticalAndDatabaseFailureUsePersistentSeverityCopyWithoutRawFailureText() {
        val critical = HealthAudit.storage(snapshot(StorageHealthSeverity.CRITICAL))
        assertTrue(critical.bannerHtml().startsWith("<div class=\"setup crit\">"))
        assertTrue(critical.warningHtml().orEmpty().contains("Recover storage headroom or address WAL growth now"))

        val failure = HealthAudit.storage(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                failureKind = StorageDatabaseFailureKind.STORAGE_FULL,
                failureOperation = "config_write",
            ),
        )
        val json = JSONObject(failure.statusJson())
        assertEquals("storage_full", json.getString("failure"))
        assertEquals("critical", json.getString("pressure_state"))
        assertTrue(failure.bannerHtml().contains("recovery is not yet verified"))
        assertTrue(failure.bannerHtml().contains("Last measured storage metrics"))
        assertTrue(failure.bannerHtml().contains("do not delete or recreate the database"))
        assertTrue(failure.diagnosticLine().contains("failure=storage_full"))
        assertFalse(failure.diagnosticLine().contains("config_write"))

        val rejected = HealthAudit.storage(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                failureKind = StorageDatabaseFailureKind.IO,
                failureOperation = "write /data/user/0/secret</div>",
            ),
        )
        assertEquals("io", JSONObject(rejected.statusJson()).getString("failure"))
        assertFalse(rejected.bannerHtml().contains("/data/"))
        assertFalse(rejected.diagnosticLine().contains("secret"))

        val corruption = HealthAudit.storage(
            snapshot(
                severity = StorageHealthSeverity.DATABASE_FAILURE,
                failureKind = StorageDatabaseFailureKind.CORRUPTION,
                failureOperation = "quick-check",
            ),
        )
        assertTrue(corruption.summary.contains("diagnostic only"))
        assertTrue(corruption.action.contains("avoid further writes"))
        assertFalse(corruption.action.contains("then retry"))
    }

    @Test fun unavailableFilesystemAndSqliteSentinelsStayJsonNull() {
        val unknown = HealthAudit.storage(
            snapshot(StorageHealthSeverity.HEALTHY).copy(
                totalBytes = 0L,
                usableBytes = 0L,
                usedPercent = null,
                pageSizeBytes = 0L,
                pageCount = 0L,
                freelistCount = 0L,
                schemaVersion = 0,
                quickCheck = StorageQuickCheck.NOT_RUN,
            ),
        )
        val json = JSONObject(unknown.statusJson())

        for (field in listOf(
            "usable_bytes", "total_bytes", "used_percent", "page_size_bytes", "page_count",
            "freelist_count", "schema_version",
        )) assertTrue("expected null $field: $json", json.isNull(field))
        assertEquals("not_run", json.getString("quick_check"))
        assertTrue(json.getString("summary").contains("free space unknown"))
    }

    @Test fun completedProbeWithUnknownCapacityRetainsSqliteEvidenceWithoutClaimingHealthy() {
        val projection = HealthAudit.storage(
            snapshot(StorageHealthSeverity.UNCHECKED).copy(
                pressureSeverity = StorageHealthSeverity.UNCHECKED,
                totalBytes = 0L,
                usableBytes = 0L,
                usedPercent = null,
            ),
        )
        val json = JSONObject(projection.statusJson())

        assertEquals("unchecked", json.getString("state"))
        assertTrue(json.isNull("usable_bytes"))
        assertTrue(json.isNull("total_bytes"))
        assertEquals(20L * MIB, json.getLong("database_bytes"))
        assertEquals(3L * MIB, json.getLong("wal_bytes"))
        assertEquals(1_234L, json.getLong("page_count"))
        assertEquals("ok", json.getString("quick_check"))
        assertEquals(1_700_000_000_000L, json.getLong("checked_at"))
        assertTrue(json.getString("summary").contains("could not be measured"))
        assertFalse(json.getString("summary").contains("healthy"))
        assertTrue(projection.diagnosticLine().contains("state=unchecked"))
        assertTrue(projection.diagnosticLine().contains("database_bytes=${20L * MIB}"))
    }

    @Test fun diagnosticsContainOnlyTheSanitizedStorageEvidenceContract() {
        val line = HealthAudit.storage(snapshot(StorageHealthSeverity.HEALTHY)).diagnosticLine()

        assertTrue(line.startsWith("[storage-health] state=healthy pressure_state=healthy"))
        for (field in listOf(
            "pressure_state", "usable_bytes", "total_bytes", "used_percent", "database_bytes", "wal_bytes",
            "sidecar_bytes", "page_size_bytes", "page_count", "freelist_count", "schema_version",
            "quick_check", "checked_at", "failure",
        )) assertTrue("missing $field: $line", "$field=" in line)
        assertFalse(line.contains("/data/"))
        assertFalse(line.contains("ha-paneld.db"))
    }

    @Test fun openApiDescribesTheStorageHealthStatusContract() {
        // Source-text reason: the shipped OpenAPI document is the public API contract.
        val api = JSONObject(File("src/main/assets/openapi.json").readText())
        val schema = api.getJSONObject("components").getJSONObject("schemas").getJSONObject("StorageHealth")
        val properties = schema.getJSONObject("properties")

        assertEquals("unchecked", properties.getJSONObject("state").getJSONArray("enum").getString(0))
        assertTrue(properties.has("usable_bytes"))
        assertTrue(properties.has("database_bytes"))
        assertTrue(properties.has("failure"))
        val status = api.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
        assertTrue(status.toString().contains("storage_health"))
        val nonce = status.getJSONArray("parameters").let { parameters ->
            (0 until parameters.length()).map { parameters.getJSONObject(it) }
                .single { it.getString("name") == "database_observation_nonce" }
        }
        assertEquals("^[0-9a-f]{32}$", nonce.getJSONObject("schema").getString("pattern"))
        assertTrue(
            status.getJSONObject("responses").getJSONObject("200").toString()
                .contains("database_observation_nonce"),
        )
    }

    private fun snapshot(
        severity: StorageHealthSeverity,
        failureKind: StorageDatabaseFailureKind? = null,
        failureOperation: String? = null,
    ): StorageHealthSnapshot = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = if (severity == StorageHealthSeverity.DATABASE_FAILURE) {
            StorageHealthSeverity.CRITICAL
        } else {
            severity
        },
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 300L * MIB,
        totalBytes = 4L * GIB,
        usedPercent = 92.7,
        mainDatabaseBytes = 20L * MIB,
        walBytes = 3L * MIB,
        sidecarBytes = 64L * 1024L,
        pageSizeBytes = 4_096L,
        pageCount = 1_234L,
        freelistCount = 45L,
        schemaVersion = 11,
        quickCheck = StorageQuickCheck.OK,
        databaseFailureKind = failureKind,
        databaseFailureOperation = failureOperation,
    )

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
