package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manager-side policies behind recovering a panel held on a strategy-generated default dashboard. */
class EntityLearningHoldRecoveryTest {
    private fun issue(type: String, fingerprint: String, ignorable: Boolean = true, blocking: Boolean = true) =
        JSONObject()
            .put("type", type)
            .put("blocking", blocking)
            .put("would_block", blocking)
            .put("ignorable", ignorable)
            .put("fingerprint", fingerprint)

    private val strategy = issue("unbounded_selector", "0123456789abcdef")
    private val fence = issue("diagnostic_limit", "fedcba9876543210", ignorable = false)
    private val advisory = issue("runtime_coverage", "1111222233334444", ignorable = false, blocking = false)

    @Test fun `only the migration latch with a retained filter is even eligible`() {
        val retained = listOf("light.kitchen", "sensor.hall")
        assertTrue(upgradeRecoveryAdmissible(true, retained))
        // A person's own reset also forces a bootstrap; it is not the migration and does not qualify.
        assertFalse(upgradeRecoveryAdmissible(false, retained))
        // No retained list means filtering was never accepted on this dashboard.
        assertFalse(upgradeRecoveryAdmissible(true, emptyList()))
    }

    @Test fun `a recovered subset is refused because it is silent canonical loss`() {
        val retained = listOf("light.kitchen", "sensor.hall", "sensor.landing")

        // The whole list, in any order, is the only admissible outcome.
        assertTrue(upgradeRecoveryPreservesFilter(retained, retained.reversed()))
        // Extra ids are fine: the dashboard may legitimately reference more than it used to.
        assertTrue(upgradeRecoveryPreservesFilter(retained, retained + "sensor.new"))

        // A subset is NOT a partial success. The apply overwrites the stored subscription and clears
        // the one-shot latch, so a dropped id is dropped for good and its card silently stops updating.
        // This is the defect the first review round found: non-empty is not sufficient.
        assertFalse(upgradeRecoveryPreservesFilter(retained, listOf("light.kitchen", "sensor.hall")))
        assertFalse(upgradeRecoveryPreservesFilter(retained, listOf("light.kitchen")))
        // A near-miss that swaps one id for another is still loss.
        assertFalse(upgradeRecoveryPreservesFilter(retained, listOf("light.kitchen", "sensor.hall", "sensor.other")))
        // The empty candidate is the fail-closed sentinel: a blank dashboard.
        assertFalse(upgradeRecoveryPreservesFilter(retained, emptyList()))
        // Nothing retained means nothing to preserve, and the decision is genuinely load-bearing.
        assertFalse(upgradeRecoveryPreservesFilter(emptyList(), listOf("light.kitchen")))
    }

    @Test fun `only ignorable blocking findings are ever eligible for a default`() {
        assertEquals(
            setOf("0123456789abcdef"),
            ignorableIssueFingerprints(listOf(strategy, fence, advisory)),
        )
        assertEquals(emptySet<String>(), ignorableIssueFingerprints(listOf(fence, advisory)))
    }

    @Test fun `the first-run default is unchanged and never carries the upgrade path`() {
        val issues = listOf(strategy, fence, advisory)
        // The upgrade path takes no default here any more; it is decided after the commit instead.
        assertEquals(
            emptySet<String>(),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = false, bootstrap = true, issues = issues,
            ),
        )
        // Only a bootstrap may take the default; an applied filter keeps its own decisions.
        assertEquals(
            emptySet<String>(),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = true, bootstrap = false, issues = issues,
            ),
        )
        // The first-run path itself is untouched by this lane: still exactly the ignorable blocking set.
        assertEquals(
            setOf("0123456789abcdef"),
            defaultIgnoredIssueFingerprints(
                initialActivationPending = true, bootstrap = true, issues = issues,
            ),
        )
    }

    @Test fun `a probe reports a change only for a different dashboard or a different revision`() {
        assertEquals(
            DashboardProbeOutcome.UNCHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace", boundPath = "/lovelace", liveRevision = "aa", storedRevision = "aa"),
        )
        assertEquals(
            DashboardProbeOutcome.CHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace", boundPath = "/lovelace", liveRevision = "bb", storedRevision = "aa"),
        )
        // The account default moved to another dashboard: the bound scope no longer describes the panel.
        assertEquals(
            DashboardProbeOutcome.CHANGED,
            dashboardProbeOutcome(resolvedPath = "/dashboard-wall", boundPath = "/lovelace", liveRevision = "aa", storedRevision = "aa"),
        )
        // A view of the same dashboard is the same scope.
        assertEquals(
            DashboardProbeOutcome.UNCHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace/kiosk", boundPath = "/lovelace", liveRevision = "aa", storedRevision = "aa"),
        )
        // Nothing committed yet: a scan is owed whatever is live.
        assertEquals(
            DashboardProbeOutcome.CHANGED,
            dashboardProbeOutcome(resolvedPath = "/lovelace", boundPath = "/lovelace", liveRevision = "aa", storedRevision = ""),
        )
    }

    @Test fun `only a credential rejection turns a failed probe into a recorded problem`() {
        assertTrue(probeFailureSurfacesProblem("states request failed: HTTP 401"))
        assertTrue(probeFailureSurfacesProblem("Home Assistant credential rejected"))
        assertTrue(probeFailureSurfacesProblem("Home Assistant token unavailable"))
        // Home Assistant being down between two probes is the reconnect case: logged, never persisted,
        // so the decision screen is not replaced by a scan-failure screen.
        assertFalse(probeFailureSurfacesProblem("connection timed out for private-host.example"))
        assertFalse(probeFailureSurfacesProblem("Timed out waiting for 60000 ms"))
        assertFalse(probeFailureSurfacesProblem(null))
    }

    @Test fun `the daily tick probes a decision hold and syncs everything else that is due`() {
        val day = 24L * 3_600_000L
        assertEquals(PeriodicMaintenanceAction.NONE, periodicMaintenanceAction(day - 1, day, heldOnDecision = true))
        assertEquals(PeriodicMaintenanceAction.NONE, periodicMaintenanceAction(day - 1, day, heldOnDecision = false))
        assertEquals(PeriodicMaintenanceAction.PROBE, periodicMaintenanceAction(day, day, heldOnDecision = true))
        assertEquals(PeriodicMaintenanceAction.SYNC, periodicMaintenanceAction(day, day, heldOnDecision = false))
    }

    @Test fun `the hold reason names what the person off-panel can act on`() {
        assertNull(entityBootstrapHoldReason(held = false, blockingIssues = 3, problem = null))
        assertEquals(
            EntityBootstrapHoldReason.DECISION,
            entityBootstrapHoldReason(held = true, blockingIssues = 1, problem = null),
        )
        assertEquals(
            EntityBootstrapHoldReason.AUTHENTICATION,
            entityBootstrapHoldReason(held = true, blockingIssues = 1, problem = EntityBootstrapProblem.AUTHENTICATION),
        )
        assertEquals(
            EntityBootstrapHoldReason.SYNCHRONIZATION,
            entityBootstrapHoldReason(held = true, blockingIssues = 0, problem = EntityBootstrapProblem.SYNCHRONIZATION),
        )
        assertEquals(
            EntityBootstrapHoldReason.SYNCHRONIZING,
            entityBootstrapHoldReason(held = true, blockingIssues = 0, problem = null),
        )
        assertEquals("decision", EntityBootstrapHoldReason.DECISION.wireName)
    }

    @Test fun `a strategy-generated default dashboard is recovered on upgrade and still asks otherwise`() {
        // Home Assistant's default dashboard as the scanner sees it: a root strategy and nothing static.
        val config = """{"strategy":{"type":"original-states"}}"""
        val lint = DashboardConfigurationLint.analyze(config, listOf("light.kitchen", "sensor.hall"), emptyMap())
        val raw = lint.issues.map(DashboardConfigurationLint.Issue::toJson)
        val flagged = raw.single()
        assertEquals("unbounded_selector", flagged.getString("type"))
        assertEquals(listOf("dashboard.strategy"), List(flagged.getJSONArray("source_locations").length()) {
            flagged.getJSONArray("source_locations").getString(it)
        })
        assertTrue(flagged.getBoolean("blocking"))
        assertTrue(flagged.getBoolean("ignorable"))

        // The reported panel: upgrade latch pending, a 347-entity filter retained, and a post-commit
        // candidate that carries every one of them.
        val retained = List(347) { "sensor.retained_$it" }
        assertTrue(upgradeRecoveryAdmissible(true, retained))
        assertTrue(upgradeRecoveryPreservesFilter(retained, retained))
        val ignored = ignorableIssueFingerprints(raw)
        val effective = JSONArray(EntityCatalogIssuePersistence.applyIgnores(JSONArray(raw), ignored))
        val issue = effective.getJSONObject(0)
        assertFalse(issue.getBoolean("blocking"))
        assertTrue(issue.getBoolean("ignored"))
        assertTrue(issue.getBoolean("would_block"))
        assertEquals("warning", issue.getString("severity"))
        assertEquals(
            AutomaticSyncDecision.BOOTSTRAP,
            automaticSyncDecision(
                learningEnabled = true, applied = false, configuredIds = emptyList(),
                blockingIssues = false, forceBootstrap = true,
            ),
        )

        // The same dashboard on a panel that never ran a filter on it keeps asking: nothing retained
        // means nothing to preserve, so no ignore is ever recorded.
        assertFalse(upgradeRecoveryAdmissible(true, emptyList()))
        val stillBlocking = JSONArray(EntityCatalogIssuePersistence.applyIgnores(JSONArray(raw), emptySet()))
        assertTrue(stillBlocking.getJSONObject(0).getBoolean("blocking"))
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(
                learningEnabled = true, applied = false, configuredIds = emptyList(),
                blockingIssues = true, forceBootstrap = true,
            ),
        )
    }

    @Test fun `the lint revision helper and the scan agree on a dashboard's identity`() {
        val config = """{"title":"Home","strategy":{"type":"original-states"}}"""
        val reordered = """{"strategy":{"type":"original-states"},"title":"Home"}"""
        val scanned = DashboardConfigurationLint.analyze(config, emptyList(), emptyMap()).dashboardRevision
        assertEquals(scanned, DashboardConfigurationLint.revision(config))
        assertEquals(scanned, DashboardConfigurationLint.revision(reordered))
        assertTrue(DashboardConfigurationLint.revision("""{"title":"Away"}""") != scanned)
    }
}
