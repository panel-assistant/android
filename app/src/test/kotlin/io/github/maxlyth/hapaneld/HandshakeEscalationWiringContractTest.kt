package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for how the handshake watchdog drives [DashboardRetryPolicy]'s escalation. The policy
 * itself is executed in `DashboardRecoveryTest`; the activity cannot be run on this gate, so its wiring
 * is asserted where it is written, in the idiom of `DeliberateRestartWiringContractTest`.
 */
class HandshakeEscalationWiringContractTest {

    private val dashboard by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt"),
        ).first { it.isFile }.readText()
    }

    private fun body(signature: String): String {
        val start = dashboard.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val end = Regex("\n    (?:override |private |internal )?fun ").find(dashboard, start + signature.length)
            ?.range?.first ?: dashboard.length
        return dashboard.substring(start, end)
    }

    private val watchdog by lazy { body("private fun onWatchdogTimeout(") }
    private val escalation by lazy { body("private fun escalateHandshakeRecovery(") }

    @Test fun everyFireIsDecidedByThePolicyWithTheScreenState() {
        val decided = watchdog.indexOf("retryPolicy.onWatchdogFired(screenAwake)")
        assertTrue("the watchdog does not ask the policy", decided >= 0)
        assertTrue(watchdog.indexOf("if (step == HandshakeRecoveryStep.NONE) return") > decided)
        // Nothing before the decision may act on the page, or a dark fire could still reload.
        val preamble = watchdog.substring(0, decided)
        assertFalse(preamble.contains("reloadTarget("))
        assertFalse(preamble.contains("v2Handshake.onTimeout("))
    }

    @Test fun aPlainMissReloadsExactlyAsBefore() {
        val branch = watchdog.indexOf("if (step != HandshakeRecoveryStep.RELOAD)")
        assertTrue("the watchdog has no escalation branch", branch >= 0)
        val reload = watchdog.substring(branch)
        assertTrue(reload.contains("escalateHandshakeRecovery(step)"))
        val branchEnd = reload.indexOf("return\n        }")
        assertTrue("the escalation branch does not return before the plain reload", branchEnd >= 0)
        val plain = reload.substring(branchEnd)
        assertTrue(plain.contains("frontend handshake watchdog fired (no connection-status:connected) — reloading"))
        assertTrue(plain.contains("if (!reloadTarget()) return"))
        assertTrue(plain.contains("armWatchdog(retryPolicy.afterRetry())"))
    }

    @Test fun escalationNeverReloadsTheCommittedDocumentAndNeverRunsFasterThanAReload() {
        assertFalse("an escalated fire must load afresh, not reload()", escalation.contains(".reload()"))
        assertFalse(escalation.contains("reloadTarget("))
        assertTrue("the fresh step must load the target afresh", escalation.contains("loadCorrectedHomeDashboard()"))
        val teardown = escalation.indexOf("teardownWeb()")
        assertTrue("the recreation step must tear the WebView down", teardown >= 0)
        assertTrue("the recreation step must rebuild after teardown", escalation.indexOf("buildAndLoad(config)") > teardown)
        assertTrue(
            "an escalated window must be no shorter than the reload it replaces",
            escalation.contains("armWatchdog(maxOf(INITIAL_HANDSHAKE_MS, retryPolicy.afterRetry()))"),
        )
    }

    @Test fun aRetryDecidesFromTheCommittedDocument() {
        val reload = body("private fun reloadTarget(")
        assertTrue(
            "reloadTarget must decide from the committed document",
            reload.contains("retryNeedsFreshLoad(committedPageUrl, config.haUrl, interstitialShown, dashboardRenderer = signInShownForUrl == null)"),
        )
        val started = body("override fun onPageStarted(")
        val recorded = started.indexOf("committedPageUrl = url")
        assertTrue("onPageStarted must record every commit", recorded >= 0)
        assertTrue(
            "the reconnecting page's commit must be recorded before its early return",
            recorded < started.indexOf("if (!dashboardNavigationAllowed(config.haUrl, url))"),
        )
        // Every renderer replacement forgets the old commit, so a new WebView starts with nothing committed.
        assertTrue(body("private fun teardownWeb(").contains("committedPageUrl = null"))
        val build = body("private fun buildCompatibleAndLoad(")
        assertTrue(build.indexOf("committedPageUrl = null") > build.indexOf("web = w"))
    }

    @Test fun escalationTakesNoCrashBudgetAndRelaunchesNothing() {
        for (forbidden in listOf(
            "consumeRebuildBudget", "allowRebuild", "fallbackToLauncher", "startActivity",
            "killProcess", "exitProcess", "finish()", "recreate()",
        )) {
            assertFalse("escalation must not call $forbidden", escalation.contains(forbidden))
        }
    }

    @Test fun escalationLogsItsStartAndRecoveryOnce() {
        val start = escalation.indexOf("if (step == HandshakeRecoveryStep.FRESH_LOAD)")
        assertTrue(start >= 0)
        assertTrue(escalation.indexOf("escalating: fresh dashboard resolution") > start)
        val connected = body("private fun onConnectionStatus(")
        val recovered = connected.indexOf("frontend handshake recovered after")
        val reset = connected.indexOf("retryPolicy.reset()")
        assertTrue(recovered >= 0)
        assertTrue("the recovery line must read the count before it is reset", recovered < reset)
        assertTrue(connected.indexOf("retryPolicy.escalatedTo?.let") in 0 until recovered)
    }
}
