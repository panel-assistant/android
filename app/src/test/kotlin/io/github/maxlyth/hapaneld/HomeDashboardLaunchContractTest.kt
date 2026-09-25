package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DashboardActivity's launch-cache wiring is lifecycle code on a real Activity (no Robolectric in
 * this repo), so the renderer-side contract is pinned against the source, the established idiom for
 * this file (see HomeDashboardResolutionGateTest). The pure policy itself is unit-tested in
 * HomeDashboardLaunchCacheTest / HomeDashboardLaunchCacheConfigTest.
 */
class HomeDashboardLaunchContractTest {

    private fun englishString(name: String): String {
        val xml = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        ).first { it.isFile }.readText()
        return Regex("""<string name="${Regex.escape(name)}"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("missing English string resource: $name")
    }

    private val source = TestSources.kotlin("DashboardActivity.kt").readText()

    private val resolver = source.substring(
        source.indexOf("private fun resolveHomeDashboardAndLoad"),
        source.indexOf("private fun navigateAfterHomeDashboardCorrection"),
    )

    private val correction = source.substring(
        source.indexOf("private fun navigateAfterHomeDashboardCorrection"),
        source.indexOf("@SuppressLint(\"SetJavaScriptEnabled\")\n    private fun buildCompatibleAndLoad"),
    )

    private val retry = source.substring(
        source.indexOf("private val admissionRetry = Runnable"),
        source.indexOf("/** Endpoint the on-panel Home Assistant sign-in is currently showing"),
    )

    @Test fun `a cached owner-matched path navigates immediately and is marked provisional`() {
        val cacheRead = resolver.indexOf("cachedHomeDashboardLaunchPath()")
        val liveResolution = resolver.indexOf("EntityLearningRuntime.resolveHomeDashboard")
        assertTrue(cacheRead in 0 until liveResolution)
        val cachedBranch = resolver.substring(cacheRead, resolver.indexOf("if (provisionalPath == null)"))
        assertTrue(cachedBranch.contains("HomeDashboardSource.CACHED"))
        assertTrue(cachedBranch.contains("confirmed = false"))
        assertTrue(cachedBranch.contains("buildCompatibleAndLoad(config, path)"))
    }

    @Test fun `the selecting screen shows only when nothing provisional is rendering`() {
        val screen = resolver.indexOf("getString(R.string.selecting_ha_dashboard)")
        val gate = resolver.indexOf("if (provisionalPath == null)")
        assertTrue(gate in 0 until screen)
        assertEquals("Selecting the Home Assistant dashboard", englishString("selecting_ha_dashboard"))
    }

    @Test fun `a transient failure keeps the cache and retries quietly behind a rendering page`() {
        val failure = resolver.substring(
            resolver.indexOf("if (resolution == null)"),
            resolver.indexOf("admissionRetryPolicy.reset()"),
        )
        // Both branches retain automatic recovery through the shared admission retry owner; the
        // teardown screen is gated on nothing rendering.
        assertTrue(failure.contains("showBlockedAdmissionScreen("))
        assertTrue(failure.contains("armAdmissionAutoRetry(it"))
        val screen = failure.indexOf("getString(R.string.dashboard_list_unavailable)")
        val gate = failure.indexOf("if (shownPath == null || web == null)")
        assertTrue(gate in 0 until screen)
        assertEquals("Home Assistant dashboard list unavailable", englishString("dashboard_list_unavailable"))
        // The transient branch never touches the persisted cache, in either direction.
        assertFalse(failure.contains("clearHomeDashboardLaunchPathIfOwned"))
        assertFalse(failure.contains("setHomeDashboardLaunchPathIfOwned"))
    }

    @Test fun `a confirmed empty dashboard list clears the cache with the screen`() {
        val confirmedNone = resolver.substring(
            resolver.indexOf("if (resolution.path == null)", resolver.indexOf("admissionRetryPolicy.reset()")),
            resolver.indexOf("setHomeDashboardLaunchPathIfOwned"),
        )
        assertTrue(confirmedNone.contains("clearHomeDashboardLaunchPathIfOwned(launchOwner)"))
        assertTrue(confirmedNone.contains("getString(R.string.no_dashboard_title)"))
        assertEquals("This account has no dashboard to open", englishString("no_dashboard_title"))
    }

    @Test fun `every successful live resolution is the only writer of the cache`() {
        // One write site in the whole renderer, inside the resolution-success path — an MQTT navigate
        // or idle return can never stamp the cache.
        assertEquals(1, Regex("setHomeDashboardLaunchPathIfOwned").findAll(source).count())
        assertTrue(resolver.contains("setHomeDashboardLaunchPathIfOwned(launchOwner, resolution.path)"))
    }

    @Test fun `a differing live answer corrects the rendering page and a matching one leaves it alone`() {
        assertTrue(resolver.contains("HomeDashboardLaunchCache.refreshOutcome(shownPath, resolution)"))
        assertTrue(resolver.contains("navigateAfterHomeDashboardCorrection(resolution.path)"))
        // The no-page path still builds, so a cacheless cold start is unchanged.
        assertTrue(resolver.contains("shownPath == null || web == null -> buildCompatibleAndLoad(currentConfig, resolution.path)"))
    }

    @Test fun `the correction prefers the live frontend bus and reloads otherwise`() {
        assertTrue(correction.contains("sendBusNavigate(path)"))
        assertTrue(correction.contains("rotateBusDocument"))
        assertTrue(correction.contains("loadUrl(target)"))
        assertTrue(correction.contains("onLoadStarted()"))
    }

    @Test fun `a navigation chosen after the provisional page outranks the correction`() {
        // Every deliberate-target site bumps the epoch…
        val busNavigate = source.substring(
            source.indexOf("private fun sendBusNavigate"),
            source.indexOf("private fun noteDeliberateDashboardNavigation"),
        )
        assertTrue(busNavigate.contains("noteDeliberateDashboardNavigation()"))
        // Seven call sites plus the declaration: bus navigate, first chosen-target load, relaunch
        // load, the correction's own full load, main-frame navigation START, redirect/renderer
        // navigation at onPageStarted, and SPA history change. A new navigation route that forgets to bump the epoch is the
        // defect this count guards.
        assertEquals(7, Regex("noteDeliberateDashboardNavigation\\(\\)").findAll(source).count() - 1)
        // …the provisional launch captures the epoch its own load set…
        assertTrue(resolver.contains("provisionalHomeDashboardEpoch = dashboardNavigationEpoch"))
        // …and the on-screen correction applies only while they still match; the store always wins.
        val corrected = resolver.substring(
            resolver.indexOf("RefreshOutcome.CORRECTED"),
            resolver.indexOf("else -> Unit"),
        )
        assertTrue(corrected.contains("if (dashboardNavigationEpoch != provisionalEpoch)"))
        assertTrue(corrected.contains("navigateAfterHomeDashboardCorrection(resolution.path)"))
        val persistIndex = resolver.indexOf("setHomeDashboardLaunchPathIfOwned(launchOwner, resolution.path)")
        assertTrue(persistIndex in 0 until resolver.indexOf("RefreshOutcome.CORRECTED"))
    }

    @Test fun `a bus correction proves itself within a bound or escalates to a full load`() {
        // The verifier is armed with the named bound, judges by the pure convergence rule, is
        // cancelled by any newer navigation, and its escalation is the shared full-load route.
        assertTrue(correction.contains("CORRECTION_VERIFY_MS"))
        assertTrue(correction.contains("HomeDashboardLaunchCache.correctionConverged(currentPath, path)"))
        assertTrue(correction.contains("dashboardNavigationEpoch != issuedEpoch) return@postDelayed"))
        assertTrue(correction.contains("loadCorrectedHomeDashboard()"))
        val fallback = source.substring(
            source.indexOf("private fun loadCorrectedHomeDashboard"),
            source.indexOf("@SuppressLint(\"SetJavaScriptEnabled\")\n    private fun buildCompatibleAndLoad"),
        )
        assertTrue(fallback.contains("rotateBusDocument"))
        assertTrue(fallback.contains("noteDeliberateDashboardNavigation()"))
        assertTrue(fallback.contains("onLoadStarted()"))
    }

    @Test fun `the scheduled retry never invalidates a provisional page it would blank`() {
        assertTrue(retry.contains("if (homeDashboardResolution?.confirmed == false)"))
        assertTrue(retry.contains("buildAndLoad(Config(this))"))
        assertTrue(retry.contains("retryAdmission(resetBackoff = false)"))
    }

    @Test fun `a failed durable invalidation keeps retrying instead of reporting convergence`() {
        val confirmedNone = resolver.substring(
            resolver.indexOf("if (resolution.path == null)", resolver.indexOf("admissionRetryPolicy.reset()")),
            resolver.indexOf("setHomeDashboardLaunchPathIfOwned"),
        )
        // The return value is consumed, not discarded: a stale row that survives deletion would be
        // replayed by the next launch or a Retry while the screen claimed a settled state.
        assertTrue(confirmedNone.contains("if (!currentConfig.clearHomeDashboardLaunchPathIfOwned(launchOwner))"))
        assertTrue(confirmedNone.contains("showBlockedAdmissionScreen("))
        assertTrue(confirmedNone.contains("AdmissionOutcome.DASHBOARD_LIST_UNREADABLE"))
        assertTrue(confirmedNone.contains("getString(R.string.no_dashboard_cache_clear_detail)"))
        assertTrue(englishString("no_dashboard_cache_clear_detail").contains("could not clear the dashboard it had saved"))
    }

    @Test fun `the resolver protocol itself never emits the cached source`() {
        val protocol = TestSources.kotlin("dashboard/EntityLearningProtocol.kt").readText()
        val resolveBody = protocol.substring(
            protocol.indexOf("fun resolveHomeDashboard("),
            protocol.indexOf("/** Accept only well-formed `mdi:` icon names"),
        )
        assertFalse(resolveBody.contains("CACHED"))
    }

    @Test fun `navigation inside the frontend outranks a pending correction`() {
        // The generation guard must observe REAL navigation (tapped link, back, SPA history change),
        // not only the app's own dispatch sites — a user who walks away from the provisional page
        // must not be yanked back by the delayed correction.
        // The file has more than one WebViewClient, so anchor the end marker AFTER the start.
        val historyHook = source.indexOf("override fun doUpdateVisitedHistory")
        val client = source.substring(
            historyHook,
            source.indexOf("override fun onPageFinished", historyHook),
        )
        assertTrue(client.contains("observedDashboardNavigationIsForeign(url)"))
        assertTrue(client.contains("noteDeliberateDashboardNavigation()"))
        // Ownership must also transfer when an allowed main-frame navigation BEGINS: a correction
        // completing between shouldOverrideUrlLoading and doUpdateVisitedHistory would otherwise
        // overwrite a navigation the user had already started.
        val override = source.substring(
            source.indexOf("override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {", historyHook - 40_000),
            historyHook,
        )
        assertTrue(override.contains("observedDashboardNavigationIsForeign(request.url.toString())"))
        assertTrue(override.contains("noteDeliberateDashboardNavigation()"))
        // Redirects and renderer-initiated navigations bypass shouldOverrideUrlLoading entirely and
        // surface at onPageStarted, which previously rotated the document without claiming.
        // Search BACKWARDS from the history hook: an earlier WebViewClient (the on-panel sign-in)
        // also defines onPageStarted, and a forward search can land on it.
        val started = source.substring(source.lastIndexOf("override fun onPageStarted", historyHook), historyHook)
        assertTrue(started.contains("if (observedDashboardNavigationIsForeign(url)) noteDeliberateDashboardNavigation()"))
        assertTrue(started.indexOf("if (expected == url) return") < started.indexOf("observedDashboardNavigationIsForeign"))
        // After the frontend connects the page is the user's: a view change inside one dashboard
        // counts, which a root-only comparison silently allowed a correction to overrule.
        val foreignFn = source.substring(
            source.indexOf("private fun observedDashboardNavigationIsForeign"),
            source.indexOf("/** The only app→frontend external-bus evaluation site."),
        )
        assertTrue(foreignFn.contains("if (frontendConnected)"))
        assertTrue(foreignFn.contains("HomeDashboardLaunchCache.sameDashboardRoute(observed, claimed)"))
        val foreign = source.substring(
            source.indexOf("private fun observedDashboardNavigationIsForeign"),
            source.indexOf("/** The only app→frontend external-bus evaluation site."),
        )
        // Before the frontend connects the page is still OUR load settling (frontend default view,
        // server redirect), so only a different dashboard ROOT counts — otherwise every launch would
        // cancel its own correction. An unparseable location is never foreign.
        assertTrue(foreign.contains("HomeDashboardLaunchCache.dashboardRootOf(observed) != HomeDashboardLaunchCache.dashboardRootOf(claimed)"))
        assertTrue(foreign.contains("?: return false"))
        // Every app-issued navigation records its target so the comparison has a baseline.
        assertTrue(Regex("noteAppNavigationTarget\\(").findAll(source).count() >= 4)
    }

    @Test fun `the refresh behind a provisional page forces a live read`() {
        assertTrue(resolver.contains("forceLive = provisionalPath != null"))
    }

    @Test fun `a lost WebView rebuilds from cache before the in-flight job short-circuits`() {
        val rebuild = resolver.indexOf(NO_RENDERER_LOG)
        val earlyReturn = resolver.indexOf("if (homeDashboardCheckingOwner == owner && homeDashboardJob?.isActive == true) return")
        assertTrue(rebuild in 0 until earlyReturn)
        val block = resolver.substring(resolver.indexOf("if (web == null) {"), earlyReturn)
        assertTrue(block.contains("cachedHomeDashboardLaunchPath()"))
        assertTrue(block.contains("buildCompatibleAndLoad(config, path)"))
        // The rebuilt resolution must ALSO be provisional and epoch-anchored. A battery survivor
        // proved this site was unpinned: marking it confirmed here would let a rebuild outlive the
        // live answer that is supposed to supersede it, and no assertion would have noticed.
        assertTrue(block.contains("HomeDashboardSource.CACHED"))
        assertTrue(block.contains("confirmed = false"))
        assertTrue(block.contains("provisionalHomeDashboardEpoch = dashboardNavigationEpoch"))
        // Both provisional sites — this one, which serves a cold start and any rebuild that tore the
        // renderer down first, and the still-held-renderer site below it — mark it unconfirmed.
        assertEquals(2, Regex("confirmed = false").findAll(resolver).count())
    }

    /**
     * Both cached launches announce themselves with one phrase, and each says which branch it is.
     *
     * The two lines used to carry unrelated wording (`rebuilt from cache` / `launched from cache`),
     * so there was no single phrase to grep for the accelerator, and the more launch-sounding of the
     * two is the one a cold start never prints. Someone checking a healthy panel for it saw nothing
     * and read the feature as broken.
     */
    @Test fun `both cached launches share one phrase and name their own branch`() {
        assertEquals(2, Regex(Regex.escape(SHARED_PHRASE)).findAll(resolver).count())
        assertEquals(2, Regex(Regex.escape(REFRESH_NOTE)).findAll(resolver).count())
        assertEquals(1, Regex(Regex.escape(NO_RENDERER_LOG)).findAll(resolver).count())
        assertEquals(1, Regex(Regex.escape(HELD_RENDERER_LOG)).findAll(resolver).count())
        // The qualifier is the whole point of the rename: one line must be findable without the
        // other, and neither may claim the branch it does not serve.
        assertTrue(resolver.contains("(cold start or rebuild)"))
        assertTrue(resolver.contains("(relaunch over the renderer this activity still holds)"))
    }

    /**
     * Each message is pinned to the branch it describes.
     *
     * The two are mutually exclusive at runtime: with no renderer the first branch accelerates the
     * launch and leaves an unconfirmed resolution behind, which makes `provisionalPath` non-null and
     * closes the second. A qualifier on the wrong branch would therefore name a situation its own
     * line can never print for — which is exactly how the previous wording misled.
     *
     * Only the qualifier is asserted, because only the qualifier can drift. The two messages capture
     * different lambda parameters (`path` and `cached`), so copying one into the other's block does
     * not compile; an absence assertion there would be enforced by the compiler and never by this
     * test, and was removed rather than kept as decoration.
     */
    @Test fun `each cached-launch message belongs to its own branch`() {
        val earlyReturn = resolver.indexOf("if (homeDashboardCheckingOwner == owner && homeDashboardJob?.isActive == true) return")

        // `renderer=none` sits inside `if (web == null) { … }`, ahead of the early return.
        val noRendererBlock = resolver.substring(resolver.indexOf("if (web == null) {"), earlyReturn)
        assertTrue(noRendererBlock.contains(NO_RENDERER_LOG))

        // `renderer=held` sits inside the cache read that runs only once the first branch declined,
        // which is only ever when a renderer from an earlier load is still held.
        val heldStart = resolver.indexOf("if (provisionalPath == null && owned == null) {", earlyReturn)
        val heldBlock = resolver.substring(heldStart, resolver.indexOf("if (provisionalPath == null) {", heldStart))
        assertTrue(heldBlock.contains(HELD_RENDERER_LOG))
    }

    private companion object {
        const val SHARED_PHRASE = "home dashboard cache-accelerated launch"
        const val REFRESH_NOTE = "the authenticated resolution refreshes behind it"
        const val NO_RENDERER_LOG = "$SHARED_PHRASE path=\$path renderer=none"
        const val HELD_RENDERER_LOG = "$SHARED_PHRASE path=\$cached renderer=held"
    }
}
