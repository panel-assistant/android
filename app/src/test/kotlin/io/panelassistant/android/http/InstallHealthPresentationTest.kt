package io.panelassistant.android.http

import io.panelassistant.android.MdnsHealth
import io.panelassistant.android.MdnsLivenessPolicy
import io.panelassistant.android.MdnsLivenessSnapshot
import io.panelassistant.android.MdnsLivenessState
import io.panelassistant.android.MdnsReasonCode
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.dashboardRecoveryPresentation
import io.panelassistant.android.mdnsHealthPresentation
import io.panelassistant.android.control.PowerRepairCapability
import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PowerSafetyAdvisoryAction
import io.panelassistant.android.control.PowerSafetyAssessment
import io.panelassistant.android.control.PowerSafetyObservation
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.control.zigbeeHealthPresentation
import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallHealthPresentationTest {
    @Test fun companionUpdateFindingReportsTheInstalledVersion() {
        val update = UpdateChecker.UpdateInfo("a translated name", "0.9.9", "1.0.0", "https://example.test/release", "companion")
        val finding = HealthAudit.evaluate(false, "", true, true, listOf(update)).single()
        assertEquals("0.9.9", HealthAudit.presentation(finding, updateComponent = "companion")?.params?.get("current"))
    }

    @Test fun healthAuditUsesStructuredInputsWithoutParsingLegacyDetail() {
        val findings = HealthAudit.evaluate(
            webViewTooOld = true,
            webViewDisplay = "System WebView 107.0",
            hasRenderer = false,
            brokerConfigured = true,
            updates = emptyList(),
            schemaRolledBack = true,
            schemaRollbackDetail = "localized-hostile detail",
        )

        assertEquals(
            "status-schema-rollback",
            HealthAudit.presentation(findings[0], fromSchema = 13, toSchema = 11)?.code,
        )
        assertEquals(
            mapOf("from_schema" to "13", "to_schema" to "11"),
            HealthAudit.presentation(findings[0], fromSchema = 13, toSchema = 11)?.params,
        )
        assertNull(HealthAudit.presentation(findings[0]))
        assertEquals(
            mapOf("current_engine" to "System WebView 107.0", "target_chromium" to "120"),
            HealthAudit.presentation(findings[1], targetChromium = 120)?.params,
        )
        assertEquals("status-webview-old", HealthAudit.presentation(findings[1], targetChromium = 120)?.code)
        assertNull(HealthAudit.presentation(findings[1]))
        assertEquals("status-no-renderer", HealthAudit.presentation(findings[2])?.code)
    }

    @Test fun updatePresentationRequiresTheProducerOwnedComponentToken() {
        val update = UpdateChecker.UpdateInfo(
            "arbitrary localized label",
            "0.9.6",
            "0.9.7",
            "https://example.test/release",
        )
        val finding = HealthAudit.evaluate(false, "", true, true, listOf(update)).single()

        assertNull(HealthAudit.presentation(finding))
        assertEquals(
            mapOf(
                "component" to "paneld",
                "current" to "0.9.6",
                "latest" to "0.9.7",
                "release_url" to "https://example.test/release",
            ),
            HealthAudit.presentation(finding, updateComponent = "paneld")?.params,
        )
        assertEquals(
            "status-update-available",
            HealthAudit.presentation(finding, updateComponent = "paneld")?.code,
        )
        assertNull(HealthAudit.presentation(finding, updateComponent = "arbitrary localized label"))
    }

    @Test fun storageWarningsExposeOnlyBoundedTypedMetrics() {
        val warning = HealthAudit.storage(storage(StorageHealthSeverity.WARNING)).warningPresentation
        assertEquals("status-storage-warning", warning?.code)
        assertEquals(
            mapOf(
                "usable_bytes" to "314572800",
                "total_bytes" to "4294967296",
                "used_percent" to "92.7",
                "database_bytes" to "20971520",
                "wal_bytes" to "3145728",
            ),
            warning?.params,
        )
        assertEquals(
            "status-storage-critical",
            HealthAudit.storage(storage(StorageHealthSeverity.CRITICAL)).warningPresentation?.code,
        )
        assertNull(HealthAudit.storage(storage(StorageHealthSeverity.HEALTHY)).warningPresentation)
        assertNull(HealthAudit.storage(StorageHealthSnapshot.UNCHECKED).warningPresentation)
    }

    @Test fun everyKnownStorageFailureUsesItsTypedCodeAndSanitizedOperation() {
        val expected = mapOf(
            StorageDatabaseFailureKind.STORAGE_FULL to "storage-full",
            StorageDatabaseFailureKind.IO to "io",
            StorageDatabaseFailureKind.CORRUPTION to "corruption",
            StorageDatabaseFailureKind.BUSY to "busy",
        )
        expected.forEach { (kind, token) ->
            val presentation = HealthAudit.storage(
                storage(
                    StorageHealthSeverity.DATABASE_FAILURE,
                    kind,
                    "catalog-maintenance",
                ),
            ).warningPresentation
            assertEquals("status-storage-database-failure", presentation?.code)
            assertEquals(token, presentation?.params?.get("failure"))
            assertEquals("catalog-maintenance", presentation?.params?.get("operation"))
        }

        assertNull(
            HealthAudit.storage(
                storage(StorageHealthSeverity.DATABASE_FAILURE, StorageDatabaseFailureKind.UNKNOWN, "database"),
            ).warningPresentation,
        )
        assertNull(
            HealthAudit.storage(
                storage(StorageHealthSeverity.DATABASE_FAILURE, StorageDatabaseFailureKind.IO, null),
            ).warningPresentation,
        )
    }

    @Test fun powerWarningsMapEveryWarningLevelAndNothingElse() {
        val expected = mapOf(
            PowerRiskLevel.AT_RISK to "status-power-at-risk",
            PowerRiskLevel.CAUTION to "status-power-caution",
            PowerRiskLevel.UNKNOWN to "status-power-unknown",
        )
        expected.forEach { (level, code) ->
            assertEquals(code, PowerSafetyPresentation.warningPresentation(power(level))?.code)
        }
        assertNull(PowerSafetyPresentation.warningPresentation(power(PowerRiskLevel.SAFE)))
    }

    @Test fun zigbeeProjectionMatchesEveryOrderedWarningBranch() {
        val expected = mapOf(
            ZigbeeHealthState.CONTAINED to "status-zigbee-contained",
            ZigbeeHealthState.CONTAINMENT_FAILED to "status-zigbee-containment-incomplete",
            ZigbeeHealthState.RUNAWAY to "status-zigbee-runaway",
            ZigbeeHealthState.DEGRADED_HIGH_CPU to "status-zigbee-high-cpu",
            ZigbeeHealthState.DEGRADED_UNJOINED to "status-zigbee-not-joined",
        )
        expected.forEach { (state, code) ->
            assertEquals(code, zigbeeHealthPresentation(ZigbeeHealthSnapshot(state = state), true)?.code)
        }
        assertNull(
            zigbeeHealthPresentation(ZigbeeHealthSnapshot(state = ZigbeeHealthState.DEGRADED_UNJOINED), false),
        )
        assertEquals(
            "status-zigbee-legacy-watchdog",
            zigbeeHealthPresentation(
                ZigbeeHealthSnapshot(
                    state = ZigbeeHealthState.HEALTHY,
                    recursiveWatchdogAssignment = true,
                ),
                true,
            )?.code,
        )
        assertEquals(
            "status-zigbee-contained",
            zigbeeHealthPresentation(
                ZigbeeHealthSnapshot(
                    state = ZigbeeHealthState.CONTAINED,
                    recursiveWatchdogAssignment = true,
                ),
                true,
            )?.code,
        )
        assertNull(zigbeeHealthPresentation(ZigbeeHealthSnapshot(state = ZigbeeHealthState.HEALTHY), true))
    }

    @Test fun dashboardRecoveryProjectionCoversTheExactStateVocabulary() {
        assertNull(dashboardRecoveryPresentation(PanelStatus.DashboardRecoveryState.NONE))
        assertEquals(
            "status-builtin-renderer-retries-stopped",
            dashboardRecoveryPresentation(PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER)?.code,
        )
        assertEquals(
            "status-external-renderer-crash-loop",
            dashboardRecoveryPresentation(PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER)?.code,
        )
    }

    @Test fun mdnsProjectionCoversEveryWarningAndUsesOnlyTypedReasons() {
        val healthy = MdnsHealth(true, "192.0.2.1", "192.0.2.1")
        assertNull(mdnsHealthPresentation(healthy))
        assertNull(mdnsHealthPresentation(MdnsHealth(false, null, null)))
        assertEquals(
            "status-mdns-not-running",
            mdnsHealthPresentation(MdnsHealth(false, null, "192.0.2.1"))?.code,
        )
        assertEquals(
            mapOf("bound_ip" to "192.0.2.1", "lan_ip" to "192.0.2.2"),
            mdnsHealthPresentation(MdnsHealth(true, "192.0.2.1", "192.0.2.2"))?.params,
        )
        assertEquals(
            "status-mdns-stale-address",
            mdnsHealthPresentation(MdnsHealth(true, "192.0.2.1", "192.0.2.2"))?.code,
        )
        assertEquals(
            mapOf("attempts" to "3", "reason_code" to "recreation-failed"),
            mdnsHealthPresentation(
                healthy.copy(
                    liveness = MdnsLivenessSnapshot(
                        state = MdnsLivenessState.EXHAUSTED,
                        recoveryAttempts = 3,
                        lastReason = "arbitrary diagnostic prose",
                        reasonCode = MdnsReasonCode.RECREATION_FAILED,
                    ),
                ),
            )?.params,
        )
        assertEquals(
            "status-mdns-unresponsive",
            mdnsHealthPresentation(
                healthy.copy(
                    liveness = MdnsLivenessSnapshot(
                        state = MdnsLivenessState.EXHAUSTED,
                        recoveryAttempts = 3,
                        reasonCode = MdnsReasonCode.RECREATION_FAILED,
                    ),
                ),
            )?.code,
        )
        assertEquals(
            mapOf("reason_code" to "no-response"),
            mdnsHealthPresentation(
                healthy.copy(liveness = MdnsLivenessSnapshot(state = MdnsLivenessState.RECOVERING)),
            )?.params,
        )
        assertEquals(
            "status-mdns-recovering",
            mdnsHealthPresentation(
                healthy.copy(liveness = MdnsLivenessSnapshot(state = MdnsLivenessState.RECOVERING)),
            )?.code,
        )
    }

    @Test fun mdnsRuntimeRecordsFiniteReasonCodesBesideExactDiagnostics() {
        val missing = MdnsLivenessPolicy(deadSweeps = 1)
        missing.observeSelf(visible = false, nowMs = 1)
        assertEquals(MdnsReasonCode.OWN_ADVERTISEMENT_ABSENT, missing.snapshot(1).reasonCode)
        assertTrue(missing.snapshot(1).lastReason.orEmpty().contains("active queries"))

        val terminal = MdnsLivenessPolicy(maxAttempts = 1)
        terminal.observeTerminalFailure(1, "opaque socket detail", MdnsReasonCode.MULTICAST_SOCKET_FAILED)
        assertEquals(MdnsReasonCode.MULTICAST_SOCKET_FAILED, terminal.snapshot(1).reasonCode)
        assertEquals("opaque socket detail", terminal.snapshot(1).lastReason)
    }

    private fun power(level: PowerRiskLevel): PowerSafetyAdvisory {
        val observation = PowerSafetyObservation(
            keepAwakeConfigured = true,
            wakeLockHeld = true,
            wifiLockRequired = true,
            wifiLockHeld = true,
            preventIdleDimConfigured = true,
            screenOffTimeoutMs = Int.MAX_VALUE,
            interactive = true,
            pluggedMask = 1,
            stayOnWhilePluggedIn = 1,
            deviceIdleMode = false,
            ignoringBatteryOptimizations = true,
            screenOffMechanism = "brightness-zero",
        )
        return PowerSafetyAdvisory(
            assessment = PowerSafetyAssessment(level, observation, emptyList(), "legacy summary", "legacy action"),
            repairCapability = PowerRepairCapability.APP_ONLY,
            action = PowerSafetyAdvisoryAction.MANUAL_ONLY,
            acknowledgementFingerprint = null,
            acknowledged = false,
        )
    }

    private fun storage(
        severity: StorageHealthSeverity,
        failureKind: StorageDatabaseFailureKind? = null,
        failureOperation: String? = null,
    ) = StorageHealthSnapshot(
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
