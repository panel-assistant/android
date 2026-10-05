package io.panelassistant.android.http

import io.panelassistant.android.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAuditTest {
    private val update = UpdateChecker.UpdateInfo("HA Companion", "2026.5.4", "2026.6.5", "https://x")

    @Test fun healthyPanelHasNoFindings() {
        val f = HealthAudit.evaluate(webViewTooOld = false, webViewDisplay = "138.0", hasRenderer = true, brokerConfigured = true, updates = emptyList())
        assertTrue(f.isEmpty())
    }

    @Test fun oldWebViewCarriesTheVersionToShow() {
        val f = HealthAudit.evaluate(webViewTooOld = true, webViewDisplay = "com.android.webview 107.0.5304.105", hasRenderer = true, brokerConfigured = true, updates = emptyList())
        assertEquals(1, f.size)
        assertEquals(HealthAudit.Kind.WEBVIEW_OLD, f[0].kind)
        assertEquals("com.android.webview 107.0.5304.105", f[0].detail)
    }

    @Test fun missingRendererIsReportedAfterBrokerConfiguration() {
        val f = HealthAudit.evaluate(
            webViewTooOld = false,
            webViewDisplay = "",
            hasRenderer = false,
            brokerConfigured = true,
            updates = emptyList(),
        )
        assertEquals(listOf(HealthAudit.Kind.NO_RENDERER), f.map { it.kind })
    }

    @Test fun missingRendererIsDeferredUntilBrokerConfiguration() {
        val f = HealthAudit.evaluate(
            webViewTooOld = false,
            webViewDisplay = "",
            hasRenderer = false,
            brokerConfigured = false,
            updates = emptyList(),
        )
        assertTrue(f.isEmpty())
    }

    @Test fun eachUpdateBecomesOneFindingCarryingItsInfo() {
        val f = HealthAudit.evaluate(webViewTooOld = false, webViewDisplay = "", hasRenderer = true, brokerConfigured = true, updates = listOf(update))
        assertEquals(1, f.size)
        assertEquals(HealthAudit.Kind.UPDATE, f[0].kind)
        assertEquals(update, f[0].update)
    }

    @Test fun findingsAreOrderedWebViewThenRendererThenUpdates() {
        val f = HealthAudit.evaluate(webViewTooOld = true, webViewDisplay = "107", hasRenderer = false, brokerConfigured = true, updates = listOf(update))
        assertEquals(
            listOf(HealthAudit.Kind.WEBVIEW_OLD, HealthAudit.Kind.NO_RENDERER, HealthAudit.Kind.UPDATE),
            f.map { it.kind },
        )
    }

    @Test fun schemaRollbackIsReportedFirstAndCarriesTheVersionDetail() {
        val f = HealthAudit.evaluate(
            webViewTooOld = true, webViewDisplay = "107", hasRenderer = false, brokerConfigured = true,
            updates = listOf(update),
            schemaRolledBack = true, schemaRollbackDetail = "schema 13 → 11",
        )
        assertEquals(HealthAudit.Kind.SCHEMA_ROLLED_BACK, f.first().kind) // most severe -> first
        assertEquals("schema 13 → 11", f.first().detail)
        assertEquals(
            listOf(
                HealthAudit.Kind.SCHEMA_ROLLED_BACK,
                HealthAudit.Kind.WEBVIEW_OLD,
                HealthAudit.Kind.NO_RENDERER,
                HealthAudit.Kind.UPDATE,
            ),
            f.map { it.kind },
        )
    }

    @Test fun noSchemaRollbackFindingByDefault() {
        val f = HealthAudit.evaluate(webViewTooOld = false, webViewDisplay = "", hasRenderer = true, brokerConfigured = true, updates = emptyList())
        assertTrue(f.none { it.kind == HealthAudit.Kind.SCHEMA_ROLLED_BACK })
    }
}
