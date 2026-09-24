package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for how the renderer asks [BuiltinDashboard.restartAnnouncements] whether to announce.
 * The policy itself is executed in `DeliberateRestartAnnouncementsTest`; the activity cannot be run on
 * this gate, so its wiring is asserted where it is written, in the idiom of
 * `StatusSurfaceWiringContractTest`.
 */
class DeliberateRestartWiringContractTest {

    private fun source(path: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$path"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$path"),
    ).first { it.isFile }.readText()

    private val dashboard by lazy { source("DashboardActivity.kt") }

    private fun body(signature: String): String {
        val start = dashboard.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val end = Regex("\n    (?:override |private |internal )?fun ").find(dashboard, start + signature.length)
            ?.range?.first ?: dashboard.length
        return dashboard.substring(start, end)
    }

    @Test fun everyRebuildOnANewIntentConsumesThePendingReload() {
        // Otherwise the next plain bring-to-foreground reloads the fresh page and announces again.
        val onNewIntent = body("override fun onNewIntent(")
        val rebuilds = Regex("""\bteardownWeb\(\)""").findAll(onNewIntent).count()
        val consumed = Regex("""BuiltinDashboard\.consumeSupersededReload\(\)""").findAll(onNewIntent).count()
        assertTrue("onNewIntent has no rebuild branches", rebuilds > 0)
        assertEquals(rebuilds, consumed)
    }

    @Test fun onlyTheLearnersReloadIsALearningRestart() {
        val onNewIntent = body("override fun onNewIntent(")
        assertTrue(
            onNewIntent.contains(
                "learning = BuiltinDashboard.consumeSupersededReload() == BuiltinDashboard.LEARNING_RELOAD_REASON",
            ),
        )
        assertTrue(source("PaneldService.kt").contains("reason = BuiltinDashboard.LEARNING_RELOAD_REASON"))
    }

    @Test fun announcementAsksThePolicyBeforeShowingAnything() {
        val announce = body("private fun announceDeliberateRestart(")
        val asked = announce.indexOf("restartAnnouncements.shouldAnnounce(")
        assertTrue(asked >= 0)
        assertTrue(asked < announce.indexOf("Toast.makeText("))
        assertTrue(announce.contains("dashboardShown = web != null"))
    }

    @Test fun theBootstrapHoldCountsAsTheLearningAnnouncement() {
        val hold = body("private fun showWaitingForEntityBootstrap(")
        assertTrue(hold.contains("restartAnnouncements.learningNoticeShown("))
        // The notice is a row of its own: the hint row is rewritten by the honesty rung after 20 s.
        assertTrue(hold.contains("rows += surface.detail(getString(R.string.entity_learning_reload_notice))"))
        assertTrue(dashboard.substringAfter("private val entityBootstrapCheck").contains("restartAnnouncements.learningNoticeShown(now)"))
    }

    @Test fun supersededReloadIsConsumedWithItsReason() {
        BuiltinDashboard.requestExplicitReload(BuiltinDashboard.LEARNING_RELOAD_REASON)
        assertEquals(BuiltinDashboard.LEARNING_RELOAD_REASON, BuiltinDashboard.consumeSupersededReload())
        assertFalse(BuiltinDashboard.consumeReloadRequest())
        assertEquals("", BuiltinDashboard.consumeReloadReason())
    }
}
