package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.dashboardEntityScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue used to hold an explicit `/lovelace/kiosk` and an `Auto` resolving to the same
 * dashboard in two namespaces, so switching modes orphaned rows and switching back revived them. What a
 * rescan cannot rebuild is exactly what was lost: runtime observations record what the panel saw, and
 * ignored issues record what a person decided.
 */
class ScopeCollapsePlanTest {

    private val root: (String) -> String = ::dashboardEntityScopePath

    @Test fun `a target already keyed by its root is left completely alone`() {
        val plan = planRouteKeyCollapse(listOf("ha" to "/lovelace", "ha" to "/alpha"), root)
        assertTrue("nothing to collapse: $plan", plan.isEmpty())
    }

    @Test fun `a route-keyed target collapses onto its dashboard root`() {
        val plan = planRouteKeyCollapse(listOf("ha" to "/lovelace/kiosk"), root)
        assertEquals(listOf(ScopeCollapse("ha", "/lovelace/kiosk", "/lovelace", mergesIntoExisting = false)), plan)
    }

    @Test fun `a route collapsing where the root already exists is a merge, not a second parent`() {
        // The root's own rows win on conflict, so an operator decision recorded in the namespace the
        // panel is actually using is never overwritten by the older one.
        val plan = planRouteKeyCollapse(listOf("ha" to "/lovelace", "ha" to "/lovelace/kiosk"), root)
        assertEquals(listOf(ScopeCollapse("ha", "/lovelace/kiosk", "/lovelace", mergesIntoExisting = true)), plan)
    }

    @Test fun `two views of one dashboard both collapse and the second sees the first arrive`() {
        // Without carrying the first step's arrival forward, the second would try to create the same
        // parent again — which is a constraint violation, not a merge.
        val plan = planRouteKeyCollapse(listOf("ha" to "/lovelace/kiosk", "ha" to "/lovelace/wall"), root)
        assertEquals(
            listOf(
                ScopeCollapse("ha", "/lovelace/kiosk", "/lovelace", mergesIntoExisting = false),
                ScopeCollapse("ha", "/lovelace/wall", "/lovelace", mergesIntoExisting = true),
            ),
            plan,
        )
    }

    @Test fun `targets never cross Home Assistant instances`() {
        val plan = planRouteKeyCollapse(listOf("ha-a" to "/lovelace", "ha-b" to "/lovelace/kiosk"), root)
        assertEquals(listOf(ScopeCollapse("ha-b", "/lovelace/kiosk", "/lovelace", mergesIntoExisting = false)), plan)
    }

    @Test fun `running the plan a second time finds nothing left to do`() {
        val rows = listOf("ha" to "/lovelace/kiosk", "ha" to "/alpha/tab")
        val after = planRouteKeyCollapse(rows, root)
            .map { it.instance to it.to }
            .distinct()
        assertTrue("the migration must be idempotent", planRouteKeyCollapse(after, root).isEmpty())
    }
}
