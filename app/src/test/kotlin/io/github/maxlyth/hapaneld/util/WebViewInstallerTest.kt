package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.device.WebViewSpec
import io.github.maxlyth.hapaneld.util.WebViewInstaller.Decision
import io.github.maxlyth.hapaneld.util.WebViewInstaller.decide
import io.github.maxlyth.hapaneld.util.WebViewInstaller.shouldSkipAutoUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewInstallerTest {
    private val rec = WebViewSpec("https://example/webview.apk", "138.0.7204.63", "abcd", "1234")
    private val MIN = 110

    @Test fun installsWhenEngineTooOldAndRecommendedNewer() {
        assertEquals(Decision.Install(rec), decide(rec, engineVersion = "107.0.5304.105", minChromium = MIN, force = false))
    }

    @Test fun skipsWhenEngineAtOrAboveThreshold() {
        // Already renders HA (e.g. the recommended build is in) — no reinstall loop.
        assertTrue(decide(rec, engineVersion = "138.0.7204.1", minChromium = MIN, force = false) is Decision.UpToDate)
        assertTrue(decide(rec, engineVersion = "110.1.0.0", minChromium = MIN, force = false) is Decision.UpToDate)
    }

    @Test fun skipsWhenEngineUnknown() {
        // Cromite-swap can leave the UA unreadable early; never act on an unknown engine.
        assertTrue(decide(rec, engineVersion = null, minChromium = MIN, force = false) is Decision.UpToDate)
    }

    @Test fun skipsWhenRecommendedNotNewerThanTooOldEngine() {
        // Threshold above the recommended build (hypothetical): don't install something no newer.
        assertTrue(decide(rec, engineVersion = "138.0.7204.63", minChromium = 200, force = false) is Decision.NotNewer)
        assertTrue(decide(rec, engineVersion = "150.0.7871.63", minChromium = 200, force = false) is Decision.NotNewer)
    }

    @Test fun noRecommendationLeavesWebViewAlone() {
        assertEquals(Decision.NoRecommendation, decide(null, engineVersion = "83.0.4103.120", minChromium = MIN, force = false))
        // …even with force (nothing to install).
        assertEquals(Decision.NoRecommendation, decide(null, engineVersion = "83.0.4103.120", minChromium = MIN, force = true))
    }

    @Test fun manualRepairReinstallsTheSameBuildWithoutDowngrading() {
        // The manual button repairs a damaged current build, but never replaces a newer engine.
        assertEquals(Decision.Install(rec), decide(rec, engineVersion = "138.0.7204.63", minChromium = MIN, force = true))
        assertTrue(decide(rec, engineVersion = "138.0.7204.64", minChromium = MIN, force = true) is Decision.NotNewer)
        assertTrue(decide(rec, engineVersion = "139.0.0.0", minChromium = MIN, force = true) is Decision.NotNewer)
        assertTrue(decide(rec, engineVersion = null, minChromium = MIN, force = true) is Decision.UpToDate)
    }

    @Test fun specMajorParses() {
        assertEquals(138, rec.major)
        assertEquals(0, WebViewSpec("u", "unknown", "c", "a").major)
    }

    // --- auto-update path (Mechanism A): advance a WORKING engine to a newer pin ---

    private val newer = WebViewSpec("https://example/lineageos-150.apk", "150.0.7871.63", "beef", "5678")

    @Test fun autoUpdateComparesAllFourComponentsAndNeverDowngrades() {
        val cases = listOf(
            "137.9.9999.9999" to Decision.Install(rec),
            "138.0.7204.1" to Decision.Install(rec),
            "138.0.7204.9" to Decision.Install(rec),
            "138.0.7203.999" to Decision.Install(rec),
            "138.0.7204.63" to Decision.NotNewer(rec.version),
            "138.0.7204.64" to Decision.NotNewer(rec.version),
            "138.0.7204.100" to Decision.NotNewer(rec.version),
            "138.1.0.0" to Decision.NotNewer(rec.version),
            "139.0.0.0" to Decision.NotNewer(rec.version),
        )
        cases.forEach { (installed, expected) ->
            assertEquals(installed, expected, decide(rec, engineVersion = installed, minChromium = MIN, force = false, autoUpdate = true))
        }
        val secondComponentPin = rec.copy(version = "138.2.7204.63")
        assertEquals(Decision.Install(secondComponentPin), decide(secondComponentPin, "138.1.9999.9999", MIN, false, true))
        assertEquals(Decision.NotNewer(secondComponentPin.version), decide(secondComponentPin, "138.3.0.0", MIN, false, true))
    }

    @Test fun autoUpdateAdvancesAWorkingEngineToANewerPin() {
        // The Cromite-147 → LineageOS-150 swap: the engine renders HA (147 ≥ 110) so the heal path leaves
        // it alone, but the auto-update path advances it to the newer pinned build.
        assertTrue(decide(newer, engineVersion = "147.0.7727.56", minChromium = MIN, force = false) is Decision.UpToDate)
        assertEquals(Decision.Install(newer), decide(newer, engineVersion = "147.0.7727.56", minChromium = MIN, force = false, autoUpdate = true))
    }

    @Test fun autoUpdateSkipsWhenPinNotNewer() {
        // Already on the pinned major (or newer) → NotNewer, so no reinstall loop after a successful swap.
        assertTrue(decide(rec, engineVersion = "138.0.7204.63", minChromium = MIN, force = false, autoUpdate = true) is Decision.NotNewer)
        assertTrue(decide(rec, engineVersion = "150.0.7871.63", minChromium = MIN, force = false, autoUpdate = true) is Decision.NotNewer)
    }

    @Test fun autoUpdateNeverActsOnUnknownEngine() {
        assertTrue(decide(rec, engineVersion = null, minChromium = MIN, force = false, autoUpdate = true) is Decision.UpToDate)
        assertTrue(decide(rec, engineVersion = "138.0.7204", minChromium = MIN, force = false, autoUpdate = true) is Decision.UpToDate)
    }

    @Test fun autoUpdateAlsoHealsABrokenEngine() {
        // Below the floor AND older than the pin → the auto-update path covers the heal case in one branch.
        assertEquals(Decision.Install(rec), decide(rec, engineVersion = "83.0.4103.120", minChromium = MIN, force = false, autoUpdate = true))
    }

    // --- loop guard for the scheduled auto-update (shouldSkipAutoUpdate) ---
    // The ONLY thing preventing a daily ~90 MB re-download (and, on the built-in renderer, an exitProcess
    // restart) on an opt-in panel where the provider won't switch. It lives in a Service method the JVM
    // suite can't exercise, so pin the extracted predicate here — a regression (record-only-on-OK, or
    // flipping the comparison) would otherwise reintroduce the loop with the whole suite still green.

    @Test fun autoUpdateGuardSkipsWhenAttemptedPinNeverBound() {
        // Signature-locked TPA10: recorded the pin last tick, cross-signer install rejected → engine still 147.
        assertTrue(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", engineVersion = "147.0.7727.56"))
    }

    @Test fun autoUpdateGuardSkipsWhenEngineUnknownAfterAttempt() {
        // Recorded the pin but the UA won't parse (records-then-can't-verify) → treat as not switched, stop.
        assertTrue(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", engineVersion = null))
    }

    @Test fun autoUpdateGuardSkipsAnAttemptedPinThatDidNotBindWithinTheSameMajor() {
        assertTrue(shouldSkipAutoUpdate("138.0.7204.63", "138.0.7204.63", "138.0.7204.1"))
        assertFalse(shouldSkipAutoUpdate("138.0.7204.63", "138.0.7204.63", "138.0.7204.63"))
    }

    @Test fun autoUpdateGuardDoesNotSkipAfterProviderBound() {
        // Successful swap: engine now at/above the pin's major → don't skip (decide then returns NotNewer,
        // so still no reinstall loop, but the guard itself must NOT short-circuit here).
        assertFalse(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", engineVersion = "150.0.7871.63"))
        assertFalse(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", engineVersion = "151.0.0.0"))
    }

    @Test fun autoUpdateGuardDoesNotSkipOnPinBump() {
        // Maintainer advanced the pin (new version string) → re-attempt even though the old one was recorded.
        assertFalse(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "151.0.0.0", engineVersion = "147.0.7727.56"))
    }

    @Test fun autoUpdateGuardDoesNotSkipOnFirstAttempt() {
        // Nothing recorded yet (fresh panel) → never skip the first attempt.
        assertFalse(shouldSkipAutoUpdate(lastVersion = "", recVersion = "150.0.7871.63", engineVersion = "147.0.7727.56"))
    }

    // A transient failure (network / staging / storage / temporarily unavailable privilege) is carried as
    // a non-terminal HealResult.Failed, so the guard keeps retrying it on the next tick.
    @Test fun autoAttemptMarkerKeepsTransientFailuresRetryable() {
        listOf(
            "download failed",
            "download staging failed",
            "insufficient storage (need 300MB)",
            "skipped: no root (su or helper daemon needed)",
            "install failed: daemon unreachable",
            "install failed: daemon busy",
            "install failed: daemon stream staging failed",
            "install outcome unknown: helper staging retained for safety",
        ).forEach { message ->
            assertFalse(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.Failed(message, terminal = false), "138.0.7204.1"))
        }
    }

    // A terminal install (success or a durable package-manager/signer rejection) and every no-op decision
    // are durable evidence, so the guard records the pin and stops re-downloading it.
    @Test fun autoAttemptMarkerRecordsTerminalProviderOutcomes() {
        assertTrue(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.Installed("OK: installed WebView 150 — reloading the dashboard"), "147.0.7727.56"))
        assertTrue(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.Failed("install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", terminal = true), "147.0.7727.56"))
        assertTrue(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.Failed("install failed: daemon install failed", terminal = true), "147.0.7727.56"))
        assertTrue(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.Failed("refused (signer mismatch)", terminal = true), "147.0.7727.56"))
        assertTrue(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.NoAction("already current (150.0.1)"), "150.0.7871.63"))
    }

    @Test fun unknownEngineDoesNotRecordAnAutoAttempt() {
        assertFalse(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.NoAction("engine unknown"), null))
        assertFalse(WebViewInstaller.shouldRecordAutoAttempt(WebViewInstaller.HealResult.NoAction("engine unknown"), "invalid"))
    }
}
