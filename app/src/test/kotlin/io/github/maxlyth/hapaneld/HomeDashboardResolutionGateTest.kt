package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardResolutionGateTest {
    @Test fun `stale endpoint credential or setting cannot admit a renderer`() {
        val gate = HomeDashboardResolutionAttemptGate()
        val firstOwner = owner("https://first.example", "first-refresh", "")
        val first = gate.start(firstOwner)
        assertTrue(gate.owns(first, firstOwner))
        assertFalse(gate.owns(first, owner("https://changed.example", "first-refresh", "")))
        assertFalse(gate.owns(first, owner("https://first.example", "replacement-refresh", "")))
        assertFalse(gate.owns(first, owner("https://first.example", "first-refresh", "/alpha")))

        val secondOwner = owner("https://first.example", "first-refresh", "/alpha")
        val second = gate.start(secondOwner)
        assertFalse(gate.owns(first, firstOwner))
        assertTrue(gate.owns(second, secondOwner))
        gate.invalidate()
        assertFalse(gate.owns(second, secondOwner))
    }

    @Test fun `initial load rebuild and reconnect share the owned legal resolution`() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val resolver = source.substring(
            source.indexOf("private fun resolveHomeDashboardAndLoad"),
            source.indexOf("@SuppressLint(\"SetJavaScriptEnabled\")\n    private fun buildCompatibleAndLoad"),
        )
        val reconnect = source.substring(
            source.indexOf("private fun reloadTarget"),
            source.indexOf("private fun doReload("),
        )
        val networkRecovery = source.substring(
            source.indexOf("private fun onNetworkAvailable"),
            source.indexOf("/** Pull-to-refresh"),
        )
        val target = source.substring(
            source.indexOf("private fun currentUrl"),
            source.indexOf("// Publish foreground state"),
        )
        val relaunch = source.substring(
            source.indexOf("override fun onNewIntent"),
            source.indexOf("// The filter endpoint reloads this singleTask activity"),
        )

        assertTrue(resolver.contains("EntityLearningRuntime.resolveHomeDashboard"))
        // An unreadable dashboard list is an incomplete check, so it recovers on its own — the screen
        // must never be terminal.
        assertTrue(resolver.contains("AdmissionOutcome.DASHBOARD_LIST_UNREADABLE"))
        assertTrue(reconnect.contains("currentUrl(Config(this))"))
        assertTrue(networkRecovery.contains("invalidateHomeDashboardResolution(resetRetry = false)"))
        assertTrue(target.contains("homeDashboardResolution"))
        assertFalse(target.contains("frontendDefaultPanel"))
        assertFalse(target.contains("ifBlank { \"/\" }"))
        assertTrue(relaunch.contains("activeHomeDashboardOwner != null"))
        assertTrue(relaunch.contains("unlatchAuth(\"dashboard authority change\")"))
        val pendingSignIn = relaunch.substring(
            relaunch.indexOf("if (haSignInPending(config.haUrl"),
            relaunch.indexOf("// Credentials arrived while the on-panel sign-in was showing"),
        )
        assertTrue(pendingSignIn.contains("signInShownForUrl == normalizedUrl"))
        assertTrue(pendingSignIn.contains("buildAndLoad(config)"))
        assertTrue(pendingSignIn.contains("return"))
        assertTrue(networkRecovery.contains("if (signInShownForUrl != null)"))
        assertTrue(networkRecovery.contains("leaving the live sign-in page intact"))
        assertFalse(networkRecovery.substringBefore("if (web == null)").contains("doReload("))
    }

    private fun owner(url: String, refresh: String, path: String) = HomeDashboardResolutionOwner(
        authOwner = HaAuthOwner(url, refresh, "client", ""),
        configuredPath = path,
    )
}
