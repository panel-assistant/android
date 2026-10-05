package io.panelassistant.android.http

import io.panelassistant.android.storage.StorageAutoVacuumMode
import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A latched `database_failure` must say which operation failed.
 *
 * The `database_failure=unknown` events reported against Issue #91 named an outcome and nothing
 * else. The operation was in fact captured and sanitized all along — it simply reached no surface,
 * so a report of one could not distinguish a maintenance pass from a settings write, and neither
 * could anyone reading it back. These tests cover the rendering; the sibling privacy assertions in
 * `StorageHealthHttpProjectionTest` and `StorageHealthRuntimeSurfaceTest` cover what must never
 * reach a surface at all.
 */
class StorageFailureAttributionTest {
    private fun snapshot(
        kind: StorageDatabaseFailureKind? = StorageDatabaseFailureKind.UNKNOWN,
        operation: String? = "catalog-maintenance",
        autoVacuum: StorageAutoVacuumMode = StorageAutoVacuumMode.INCREMENTAL,
        severity: StorageHealthSeverity = StorageHealthSeverity.DATABASE_FAILURE,
    ) = StorageHealthSnapshot(
        severity = severity,
        pressureSeverity = StorageHealthSeverity.HEALTHY,
        checkedAtMillis = 1_700_000_000_000L,
        usableBytes = 900L * 1024L * 1024L,
        totalBytes = 4L * 1024L * 1024L * 1024L,
        usedPercent = 40.0,
        mainDatabaseBytes = 20L * 1024L * 1024L,
        walBytes = 512L * 1024L,
        sidecarBytes = 32L * 1024L,
        pageSizeBytes = 4_096L,
        pageCount = 4_739L,
        freelistCount = 2_354L,
        schemaVersion = 14,
        quickCheck = StorageQuickCheck.OK,
        autoVacuumMode = autoVacuum,
        databaseFailureKind = kind,
        databaseFailureOperation = operation,
    )

    @Test fun theFailingOperationReachesTheStatusJsonAndTheDiagnosticLine() {
        val presentation = HealthAudit.storage(snapshot())
        val json = JSONObject(presentation.statusJson())

        assertEquals("unknown", json.getString("failure"))
        // `has` first, then `optString`: an absent key must fail this assertion rather than throw,
        // so a regression reads as this contract breaking and not as an incidental JSON error.
        assertTrue("the status JSON must carry the operation", json.has("failure_operation"))
        assertEquals(
            "an unknown outcome is exactly when the operation is the only usable detail",
            "catalog-maintenance",
            json.optString("failure_operation"),
        )
        assertTrue(presentation.diagnosticLine().contains("failure_operation=catalog-maintenance"))
    }

    @Test fun theFailingOperationIsNamedInTheOperatorFacingSummary() {
        // The machine fields serve a diagnostic dump; the summary is what someone actually pastes.
        assertTrue(
            HealthAudit.storage(snapshot()).summary.contains("during catalog-maintenance"),
        )
        assertTrue(
            HealthAudit.storage(snapshot(kind = StorageDatabaseFailureKind.BUSY))
                .summary.contains("during catalog-maintenance"),
        )
    }

    @Test fun everyFailureKindNamesItsOperation() {
        for (kind in StorageDatabaseFailureKind.entries) {
            val presentation = HealthAudit.storage(snapshot(kind = kind, operation = "app-state-write"))
            assertTrue(
                "$kind must name its operation in the summary",
                presentation.summary.contains("app-state-write"),
            )
            assertEquals(
                "$kind must name its operation in the status JSON",
                "app-state-write",
                JSONObject(presentation.statusJson()).optString("failure_operation"),
            )
        }
    }

    @Test fun anOperationOutsideTheClosedVocabularyIsReducedRatherThanRendered() {
        // Capture sanitizes, but `copy` reaches the field directly, so the render boundary reduces
        // again. Anything unrecognized becomes the generic label — a path or a query fragment can
        // never reach a user-visible string by construction.
        val presentation = HealthAudit.storage(
            snapshot(operation = "SELECT secret FROM /data/user/0/private.db"),
        )
        val rendered = presentation.statusJson() + presentation.summary + presentation.diagnosticLine()

        assertEquals("database", JSONObject(presentation.statusJson()).optString("failure_operation"))
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("/data/"))
        assertFalse(rendered.contains("SELECT"))
    }

    @Test fun aFailureWithNoRecordedOperationSaysSoRatherThanInventingOne() {
        val presentation = HealthAudit.storage(snapshot(operation = null))

        assertTrue(presentation.diagnosticLine().contains("failure_operation=none"))
        assertFalse(
            "an absent operation must not produce a dangling 'during'",
            presentation.summary.contains("during"),
        )
        assertFalse(JSONObject(presentation.statusJson()).has("failure_operation"))
    }

    @Test fun aBlankOperationIsTreatedAsAbsentRatherThanRenderedEmpty() {
        val presentation = HealthAudit.storage(snapshot(operation = "   "))

        assertTrue(presentation.diagnosticLine().contains("failure_operation=none"))
        assertFalse(presentation.summary.contains("during"))
    }

    @Test fun theAutoVacuumModeIsAlwaysReportedSoALargeFreelistCanBeInterpreted() {
        // Without it, a big freelist_count is ambiguous: reclamation lagging, or a database on which
        // bounded reclamation can never run at all.
        for (mode in StorageAutoVacuumMode.entries) {
            val presentation = HealthAudit.storage(
                snapshot(autoVacuum = mode, severity = StorageHealthSeverity.HEALTHY, kind = null),
            )
            val expected = mode.name.lowercase()
            assertEquals(expected, JSONObject(presentation.statusJson()).getString("auto_vacuum"))
            assertTrue(presentation.diagnosticLine().contains("auto_vacuum=$expected"))
        }
    }

    @Test fun theStatusJsonAndTheOpenApiSchemaDescribeTheSameStorageFields() {
        // `/api/v1/status` is a documented public contract, and its documentation drifts silently:
        // the `failure` description claimed no operation detail was ever exposed long after the
        // operation was being captured. A green suite proves nothing here unless something compares
        // the two, so this does.
        // Source-text reason: the shipped OpenAPI schema is the public API wire format.
        val assets = listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
            .map { File(it) }.firstOrNull { it.isDirectory }
        assumeTrue("assets dir not found (skipping)", assets != null)
        val schema = JSONObject(File(assets, "openapi.json").readText())
            .getJSONObject("components").getJSONObject("schemas").getJSONObject("StorageHealth")
        val documented = schema.getJSONObject("properties").keys().asSequence().toSet()

        // A failed snapshot after a refused rebuild emits every optional field, so its key set is the
        // widest the API produces.
        val remediated = snapshot().copy(
            remediation = io.panelassistant.android.storage.StorageRemediationSummary(
                ranAtMillis = 1L,
                verdict = io.panelassistant.android.storage.StorageRemediationVerdict.EXHAUSTED,
                filesDeleted = 0,
                fileBytesFreed = 0L,
                databaseBytesFreed = 0L,
                retention = io.panelassistant.android.storage.RetentionResult.SKIPPED_DATABASE_FAILURE,
                walCheckpoint = io.panelassistant.android.storage.WalCheckpointResult.SKIPPED_DATABASE_FAILURE,
                vacuum = io.panelassistant.android.storage.VacuumOutcome(
                    io.panelassistant.android.storage.VacuumResult.REFUSED,
                    io.panelassistant.android.storage.VacuumRefusal.DATABASE_FAILURE,
                ),
                finalPressure = StorageHealthSeverity.CRITICAL,
            ),
        )
        val emitted = JSONObject(HealthAudit.storage(remediated).statusJson()).keys().asSequence().toSet()
        assertTrue("undocumented status fields: ${emitted - documented}", (emitted - documented).isEmpty())
        assertTrue("documented but never emitted: ${documented - emitted}", (documented - emitted).isEmpty())

        val required = schema.getJSONArray("required").let { array ->
            (0 until array.length()).map(array::getString).toSet()
        }
        val healthy = JSONObject(
            HealthAudit.storage(
                snapshot(severity = StorageHealthSeverity.HEALTHY, kind = null, operation = null),
            ).statusJson(),
        ).keys().asSequence().toSet()
        assertTrue("required fields missing from a healthy status: ${required - healthy}",
            (required - healthy).isEmpty())
        assertTrue("auto_vacuum must be documented as always present", required.contains("auto_vacuum"))
        assertFalse(
            "failure_operation is present only alongside a failure, so it must not be required",
            required.contains("failure_operation"),
        )
        assertTrue(
            "remediation fields are present only after a run, so none may be required",
            required.none { it.startsWith("remediation") },
        )
    }

    @Test fun aHealthySnapshotCarriesNoFailureOperationField() {
        val presentation = HealthAudit.storage(
            snapshot(severity = StorageHealthSeverity.HEALTHY, kind = null, operation = null),
        )
        val json = JSONObject(presentation.statusJson())

        assertFalse(json.has("failure"))
        assertFalse(json.has("failure_operation"))
        assertTrue(presentation.diagnosticLine().contains("failure=none"))
    }
}
