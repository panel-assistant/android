package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Auto` names no dashboard of its own — it means whatever this Home Assistant account's default
 * resolves to, which only an authenticated read can supply. The scope it keeps is therefore a standing
 * binding, and the scan has to correct it the moment a resolution disagrees.
 *
 * The decision is pure and tested directly. The wiring around it needs a live WebSocket scan, so its
 * ordering is pinned against the source instead of claimed as executed coverage.
 */
class AutoResolvedScopeContractTest {
    private val manager = File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt").readText()

    @Test fun aResolutionNamingAnotherDashboardRequiresARebind() {
        // Same dashboard, whatever the route below it: the learned list is identical, so it stays.
        assertFalse(resolvedScopeRequiresRebind("/alpha", "/alpha"))
        assertFalse(resolvedScopeRequiresRebind("/alpha", "/alpha/music"))
        assertFalse(resolvedScopeRequiresRebind("/alpha/music", "/alpha/beta"))
        assertFalse(resolvedScopeRequiresRebind("/alpha", "/alpha/view?kiosk=1#main"))

        // A different dashboard is a different document; inheriting the old list would filter out
        // entities the new one renders and its cards would quietly stop updating.
        assertTrue(resolvedScopeRequiresRebind("/alpha", "/beta-dash"))
        // A panel with no established dashboard adopts whatever the default resolved to.
        assertTrue(resolvedScopeRequiresRebind("/", "/alpha"))
    }

    @Test fun theScanActsOnTheResolvedScopeBeforeFetchingTheDashboard() {
        // Order is the point: reconciling AFTER the fetch would scan the wrong document first, and
        // reconciling without abandoning the pass would let it commit against the superseded scope.
        val reconcile = manager.indexOf("if (reconcileResolvedScope(snapshot, resolved))")
        val fetch = manager.indexOf("val urlPath = EntityLearningProtocol.dashboardUrlPath(resolved)")
        assertTrue("the scan must reconcile the resolved scope", reconcile > 0)
        assertTrue("reconciliation must precede the dashboard fetch", reconcile < fetch)
        assertTrue(
            "a retargeted pass must abandon rather than commit against the superseded scope",
            manager.contains("error(\"entity-learning scope retargeted to the resolved dashboard\")"),
        )
        // The rerun is latched, never started nested: syncNow records a reason while a sync is active.
        assertTrue(manager.contains("syncNow(\"auto-resolved-scope\")"))
        assertFalse(
            "reconciliation must not call the synchronized retarget from inside the scan coroutine",
            manager.substringAfter("private fun reconcileResolvedScope(")
                .substringBefore("\n    }")
                .contains("onTargetConfigurationChanged("),
        )
    }
}
