package io.github.maxlyth.hapaneld.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions behind Auto's resolved scope, asserted where they are made. Both defects they
 * answer were invisible to evidence that read source text: one is a cache whose key cannot express the
 * change it is supposed to notice, the other a durable write that happens after its own currency check.
 */
class AutoResolvedScopeDecisionTest {

    @Test fun `Auto must resolve live because its memo key cannot express the account default moving`() {
        for (auto in listOf("", "   ", "/")) {
            assertTrue("`$auto` names no dashboard, so only a live read can answer it",
                homeDashboardResolutionMustBeLive(auto))
        }
    }

    @Test fun `an explicitly chosen dashboard may reuse the memo within one pass`() {
        // The configured path is part of the key here, so a change to it already invalidates the memo.
        // Forcing a live read for these would cost a round trip per scan and prove nothing.
        for (explicit in listOf("/lovelace", "/lovelace/kiosk", "/alpha/tab")) {
            assertFalse(explicit, homeDashboardResolutionMustBeLive(explicit))
        }
    }

    @Test fun `a rebind is written only when every part of its snapshot still describes the panel`() {
        assertTrue(
            resolvedScopeRebindIsStillCurrent(
                snapshotConfigured = "", currentConfigured = "",
                snapshotOrigin = "https://ha.example", currentOrigin = "https://ha.example",
                generationMatches = true,
            ),
        )
    }

    @Test fun `each component alone is enough to abandon the rebind`() {
        // A durable write on a stale snapshot would overwrite a newer selection with an older one, so
        // no single component may be allowed to disagree.
        assertFalse(
            "the configured dashboard moved under a cancelled pass",
            resolvedScopeRebindIsStillCurrent(
                snapshotConfigured = "", currentConfigured = "/alpha",
                snapshotOrigin = "https://ha.example", currentOrigin = "https://ha.example",
                generationMatches = true,
            ),
        )
        assertFalse(
            "the endpoint moved under a cancelled pass",
            resolvedScopeRebindIsStillCurrent(
                snapshotConfigured = "", currentConfigured = "",
                snapshotOrigin = "https://ha.example", currentOrigin = "https://other.example",
                generationMatches = true,
            ),
        )
        assertFalse(
            "a newer effect generation owns the target now",
            resolvedScopeRebindIsStillCurrent(
                snapshotConfigured = "", currentConfigured = "",
                snapshotOrigin = "https://ha.example", currentOrigin = "https://ha.example",
                generationMatches = false,
            ),
        )
    }

    @Test fun `Auto schedules a startup scan even when a previous sync certified a filter`() {
        // The defect this closes: every other reason here is a property of STORED state, and stored
        // state cannot record that the account default moved while the panel was stopped. A nonzero
        // lastSyncAt therefore certified a filter for the dashboard the panel used to show, so the
        // renderer opened the new one while the old allow-list stayed active. Forcing a live resolution
        // inside the scan cannot help when this decision is what determines whether a scan runs at all.
        assertTrue(
            shouldSyncEntityLearningOnStartup(
                learningEnabled = true,
                lastSyncAt = 1_700_000_000_000L,
                resolverMigration = io.github.maxlyth.hapaneld.DashboardEntityDefaultResolverMigration.NOT_NEEDED,
                autoScopeUnverifiedThisProcess = true,
            ),
        )
    }

    @Test fun `an explicit dashboard with a certified filter still skips the startup scan`() {
        // The scope is configured, not discovered, so there is nothing a live read could tell us that
        // the stored state does not already say. Scanning anyway would be a round trip every boot.
        assertFalse(
            shouldSyncEntityLearningOnStartup(
                learningEnabled = true,
                lastSyncAt = 1_700_000_000_000L,
                resolverMigration = io.github.maxlyth.hapaneld.DashboardEntityDefaultResolverMigration.NOT_NEEDED,
                autoScopeUnverifiedThisProcess = false,
            ),
        )
    }

    @Test fun `a failed resolver migration still refuses to scan, Auto or not`() {
        // Fail-closed outranks scope verification: the filter is already suspect.
        assertFalse(
            shouldSyncEntityLearningOnStartup(
                learningEnabled = true,
                lastSyncAt = 0L,
                resolverMigration = io.github.maxlyth.hapaneld.DashboardEntityDefaultResolverMigration.PERSIST_FAILED,
                autoScopeUnverifiedThisProcess = true,
            ),
        )
    }
}
