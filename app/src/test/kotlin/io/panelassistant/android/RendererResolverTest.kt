package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINIMAL = "io.homeassistant.companion.android.minimal"
private const val FULL = "io.homeassistant.companion.android"
private const val OWN = "io.github.maxlyth.hapaneld"

/**
 * Differential coverage for the one renderer-target owner. Each policy is asserted across the full
 * configured/available matrix that the three former rule homes (SystemController.resolveDashboard,
 * resolveAdminDashboardTarget, PaneldService.dashboardTarget) each implemented separately, plus
 * automatic built-in selection and explicit foreign-renderer preservation.
 */
class RendererResolverTest {
    // --- shared: legacy cleanup set + built-in selection ---------------------------------------------

    @Test fun `legacy Companion package set is minimal-first and complete`() {
        assertEquals(listOf(MINIMAL, FULL), RendererResolver.LEGACY_COMPANION_PACKAGES)
        assertEquals(setOf(MINIMAL, FULL), RendererResolver.LEGACY_COMPANION_PACKAGE_SET)
    }

    @Test fun `only a configured Companion returns to this panels exact route`() {
        val route = "/lovelace/this-panel?theme=dark#view"
        assertEquals(route, RendererResolver.companionHomeRoute(MINIMAL, route))
        assertEquals(route, RendererResolver.companionHomeRoute(FULL, route))
        assertNull(RendererResolver.companionHomeRoute(MINIMAL, ""))
        assertNull(RendererResolver.companionHomeRoute(MINIMAL, "/"))
        assertNull(RendererResolver.companionHomeRoute("com.example.renderer", route))
        assertNull(RendererResolver.companionHomeRoute(MINIMAL, "https://other.example/lovelace"))
        assertNull(RendererResolver.companionHomeRoute(MINIMAL, "/lovelace/../auth"))
        assertNull(RendererResolver.companionHomeRoute(MINIMAL, "/lovelace/%2e%2e/auth"))
    }

    @Test fun `built-in selection is the sentinel or the own-package alias`() {
        assertTrue(RendererResolver.isBuiltinSelection("builtin", OWN))
        assertTrue(RendererResolver.isBuiltinSelection(OWN, OWN))
        assertEquals(false, RendererResolver.isBuiltinSelection(FULL, OWN))
        assertTrue(RendererResolver.isBuiltinSelection("", OWN))
    }

    // --- control resolution (what SystemController acts on) -----------------------------------------

    @Test fun `control passes the builtin sentinel through unchanged`() {
        assertEquals("builtin", RendererResolver.resolveControlPackage("builtin") { false })
    }

    @Test fun `control retains a valid explicit foreign package even when uninstalled`() {
        assertEquals("com.example.renderer", RendererResolver.resolveControlPackage("com.example.renderer") { false })
    }

    @Test fun `control rejects a structurally invalid non-blank selection`() {
        assertEquals("", RendererResolver.resolveControlPackage("not a package!") { true })
    }

    @Test fun `control auto selects builtin even when a Companion is installed`() {
        val probed = mutableListOf<String>()
        val resolved = RendererResolver.resolveControlPackage("") { probed += it; it == FULL }
        assertEquals("builtin", resolved)
        assertTrue(probed.isEmpty())
    }

    @Test fun `control auto resolves to builtin without probing packages`() {
        assertEquals("builtin", RendererResolver.resolveControlPackage("") { false })
    }

    // --- reported resolution (what an API client reads instead of re-deriving the rule) -------------
    //
    // The installer's dashboard-seed gate is the first client. It cannot derive this: a blank
    // `dashboard_package` selects the built-in renderer, so the stored value answers a different
    // question, and gating on it refused panels that were running the built-in renderer all along.

    @Test fun `reported resolution passes the builtin sentinel through`() {
        // Also the guard on the sentinel's own spelling: the projection publishes it because it satisfies
        // the package grammar, so a sentinel changed to something shaped like `<builtin>` would silently
        // start reporting "no renderer" and every client would read the panel as unresolvable.
        assertEquals("builtin", RendererResolver.reportedRenderer(RendererResolver.BUILTIN))
    }

    @Test fun `reported resolution of an automatic selection is the built-in renderer`() {
        // The whole point of the projection: blank in, built-in out, with no client-side interpretation.
        assertEquals("builtin", RendererResolver.reportedRenderer(RendererResolver.resolveControlPackage("") { false }))
    }

    @Test fun `reported resolution passes a foreign package through`() {
        assertEquals(MINIMAL, RendererResolver.reportedRenderer(RendererResolver.resolveControlPackage(MINIMAL) { true }))
    }

    @Test fun `reported resolution of a structurally invalid selection names no renderer`() {
        // Distinct from a foreign renderer on purpose: "running something else" and "cannot resolve what
        // it runs" need different answers from the caller, so they must not share a representation.
        assertEquals("", RendererResolver.reportedRenderer(RendererResolver.resolveControlPackage("not a package!") { true }))
    }

    @Test fun `reported resolution of the invalid-dashboard sentinel names no renderer`() {
        // SystemController.resolveDashboard reports a corrupt stored selection with its own sentinel
        // rather than an empty string; that is not a package and must not be published as one.
        assertEquals("", RendererResolver.reportedRenderer("<invalid-dashboard>"))
    }

    @Test fun `reported resolution mirrors the resolver rather than the selection rule`() {
        // The own-package alias counts as a built-in SELECTION, but control resolution keeps it as the
        // package it is, and every other consumer of the resolved value treats it that way. The report
        // says what the panel resolved, so a client and the panel can never disagree about the renderer.
        assertEquals(OWN, RendererResolver.reportedRenderer(RendererResolver.resolveControlPackage(OWN) { true }))
    }

    // --- launchable resolution (what Main/Admin can open now) ---------------------------------------

    @Test fun `launchable ready builtin is available without a Companion`() {
        assertEquals(RendererTarget.Builtin, RendererResolver.resolveLaunchable("builtin", builtinReady = true) { false })
    }

    @Test fun `launchable unready builtin does not fall back to a Companion`() {
        assertNull(RendererResolver.resolveLaunchable("builtin", builtinReady = false) { true })
    }

    @Test fun `launchable foreign renderer is selected when launchable`() {
        assertEquals(
            RendererTarget.Foreign("com.example.renderer"),
            RendererResolver.resolveLaunchable("com.example.renderer", builtinReady = false) { it == "com.example.renderer" },
        )
    }

    @Test fun `launchable missing explicit foreign never falls back silently`() {
        assertNull(RendererResolver.resolveLaunchable("com.example.renderer", builtinReady = false) { it == MINIMAL })
    }

    @Test fun `launchable blank selects ready builtin even when a Companion is launchable`() {
        val probed = mutableListOf<String>()
        assertEquals(RendererTarget.Builtin, RendererResolver.resolveLaunchable("", builtinReady = true) { probed += it; true })
        assertTrue(probed.isEmpty())
    }

    @Test fun `launchable blank selects ready builtin`() {
        assertEquals(RendererTarget.Builtin, RendererResolver.resolveLaunchable("", builtinReady = true) { false })
    }

    @Test fun `launchable blank suppresses the tile without a ready builtin`() {
        assertNull(RendererResolver.resolveLaunchable("", builtinReady = false) { false })
    }

    // --- perf attribution (derived from the resolved control package) -------------------------------

    @Test fun `attribution maps the builtin sentinel to the own package`() {
        val target = RendererResolver.resolveControlTarget("builtin") { false }
        assertEquals(OWN, RendererResolver.attributionOf(target, OWN))
    }

    @Test fun `attribution passes an explicit foreign package through`() {
        val target = RendererResolver.resolveControlTarget("com.example.renderer") { false }
        assertEquals("com.example.renderer", RendererResolver.attributionOf(target, OWN))
    }

    @Test fun `attribution of automatic selection is the built-in renderer`() {
        val target = RendererResolver.resolveControlTarget("") { it == MINIMAL }
        assertEquals(OWN, RendererResolver.attributionOf(target, OWN))
    }

    @Test fun `attribution of auto builtin fallback maps to the own package`() {
        val target = RendererResolver.resolveControlTarget("") { false }
        assertEquals(OWN, RendererResolver.attributionOf(target, OWN))
    }
}
