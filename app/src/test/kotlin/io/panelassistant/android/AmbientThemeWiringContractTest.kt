package io.panelassistant.android

import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.util.DashboardTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ambient reaches every themed surface through the effective policy, and a new verdict reaches the
 * dashboard through the same rebuild a Dashboard theme change uses.
 */
class AmbientThemeWiringContractTest {

    // --- when a verdict has to rebuild the dashboard ----------------------------------------------

    @Test fun `a verdict the page does not carry rebuilds the dashboard in front`() {
        assertTrue(ambientThemeNeedsRebuild(DashboardTheme.DARK, DashboardTheme.LIGHT, builtinRenderer = true, foreground = true))
        assertTrue(ambientThemeNeedsRebuild(DashboardTheme.FOLLOW, DashboardTheme.DARK, builtinRenderer = true, foreground = true))
    }

    @Test fun `a page that already carries the verdict is left alone`() {
        assertFalse(ambientThemeNeedsRebuild(DashboardTheme.DARK, DashboardTheme.DARK, builtinRenderer = true, foreground = true))
    }

    @Test fun `nothing is pulled to the front or rebuilt for a renderer that is not ours`() {
        assertFalse(ambientThemeNeedsRebuild(DashboardTheme.DARK, DashboardTheme.LIGHT, builtinRenderer = true, foreground = false))
        assertFalse(ambientThemeNeedsRebuild(DashboardTheme.DARK, DashboardTheme.LIGHT, builtinRenderer = false, foreground = true))
        assertFalse(ambientThemeNeedsRebuild(DashboardTheme.DARK, null, builtinRenderer = true, foreground = true))
    }

    @Test fun `one rebuild is requested per resolution so a relaunch that cannot rebuild does not loop`() {
        assertFalse(
            ambientThemeNeedsRebuild(
                DashboardTheme.DARK, DashboardTheme.LIGHT, builtinRenderer = true, foreground = true,
                alreadyRequested = DashboardTheme.DARK,
            ),
        )
        assertTrue(
            "a new resolution is requested even after an earlier one",
            ambientThemeNeedsRebuild(
                DashboardTheme.LIGHT, DashboardTheme.DARK, builtinRenderer = true, foreground = true,
                alreadyRequested = DashboardTheme.DARK,
            ),
        )
    }

    @Test fun `a dashboard returning to the front is checked again`() {
        // Source-text reason: a lifecycle registration. The service's display owner registers the listener in its
        // start sequence and clears it in teardown; what reconciliation then does is pinned in AmbientThemeConsentTest.
        val service = source("ServiceDisplay.kt")
        val registration = slice(service, "BuiltinDashboard.setForegroundGainedListener {", "\n        }\n")
        assertTrue(registration.contains("reconcileAmbientTheme()"))
        assertTrue("teardown must clear it", service.contains("BuiltinDashboard.setForegroundGainedListener(null)"))
    }

    @Test fun `the return listener fires only when the dashboard comes to the front`() {
        val owner = BuiltinDashboard.acquireActivityOwner()
        var gained = 0
        try {
            BuiltinDashboard.setActivityForeground(owner, false)
            BuiltinDashboard.setForegroundGainedListener { gained++ }
            BuiltinDashboard.setActivityForeground(owner, true)
            assertEquals("coming to the front fires once", 1, gained)
            BuiltinDashboard.setActivityForeground(owner, true)
            assertEquals("staying in front does not fire again", 1, gained)
            BuiltinDashboard.setActivityForeground(owner, false)
            assertEquals("leaving the front does not fire", 1, gained)
            BuiltinDashboard.setActivityForeground(owner, true)
            assertEquals("each return fires", 2, gained)
            BuiltinDashboard.setForegroundGainedListener(null)
            BuiltinDashboard.setActivityForeground(owner, false)
            BuiltinDashboard.setActivityForeground(owner, true)
            assertEquals("a cleared listener is not called", 2, gained)
        } finally {
            BuiltinDashboard.setForegroundGainedListener(null)
            BuiltinDashboard.releaseActivityOwner(owner)
        }
    }

    // --- source wiring ------------------------------------------------------------------------------

    @Test fun `the controller judges the model's own output on the room's own scale`() {
        val controller = source("control/AutoBrightnessController.kt")
        val tick = slice(controller, "private fun applyEvaluation(", "\n    }\n")
        assertTrue("the evaluation's next delay honours the dwell", tick.contains("nextDelayMs = ambientAwareDelay("))
        assertTrue(
            tick.contains(
                "lastAmbientLevel = AdaptiveLuxCurve.normalizedLevel(result.effectiveLux, result.estimate.brightnessRange)",
            ),
        )
        assertTrue(tick.contains("if (ambientTheme.observe(nowElapsed, lastAmbientLevel)) publishAmbientVerdict()"))
        val delay = slice(controller, "private fun ambientAwareDelay(", "\n    }\n")
        assertTrue("a pending change is judged when its dwell ends", delay.contains("ambientTheme.pendingDeadlineMs()"))
    }

    @Test fun `a verdict is persisted and handed to the service off the controller's lock`() {
        val publish = source("control/AutoBrightnessController.kt")
            .substringAfter("private fun publishAmbientVerdict() {").substringBefore("\n    }\n")
        assertTrue(publish.contains("config.setDashboardAmbientDark(dark)"))
        assertTrue(publish.contains("scheduler.execute { if (!closed) onAmbientThemeChanged() }"))
        val service = source("ServiceDisplay.kt")
        assertTrue(service.contains("onAmbientThemeChanged = ::reconcileAmbientTheme"))
        assertTrue(service.contains("private fun reconcileAmbientTheme(): Unit = synchronized(ambientRebuildLock) {"))
        val reconcile = slice(service, "private fun reconcileAmbientTheme()", "\n    }\n")
        assertTrue(reconcile.contains("val applied = BuiltinDashboard.appliedThemeSignature"))
        assertTrue(reconcile.contains("appliedSignature = applied,"))
    }

    @Test fun `native screens follow the ambient verdict over a contrary Home Assistant preference`() {
        val resources = object : Resources(null, null, null) {
            override fun getConfiguration(): Configuration = Configuration().apply {
                uiMode = Configuration.UI_MODE_NIGHT_NO
            }
        }
        val activity = object : Activity() {
            override fun getPackageName(): String = "io.panelassistant.android"
            override fun getResources(): Resources = resources
        }
        for (roomDark in listOf(true, false)) {
            val prefs = fakePreferences(initial = mapOf(
                "dashboard_theme" to DashboardTheme.AMBIENT,
                "auto_brightness" to true,
                "dashboard_theme_ambient_dark" to roomDark,
                "dashboard_theme_dark" to !roomDark,
            ))
            assertEquals("native surface follows the room", roomDark, StatusSurface.darkFor(activity, Config(prefs.instance)))
            prefs.values["auto_brightness"] = false
            assertEquals("without the ambient model it follows Home Assistant", !roomDark, StatusSurface.darkFor(activity, Config(prefs.instance)))
        }
    }

    @Test fun `the ambient verdict and auto-brightness redraw the status surfaces`() {
        val listener = source("DashboardActivity.kt")
            .substringAfter("private val rendererPowerListener").substringBefore("convergeStatusTheme() }")
        assertTrue(listener.contains("key == \"dashboard_theme_ambient_dark\""))
        assertTrue(listener.contains("key == \"auto_brightness\""))
    }

    /** The text between [start] and [end]; fails rather than answering with the whole file when either is missing. */
    private fun slice(text: String, start: String, end: String): String {
        assertTrue("missing: $start", text.contains(start))
        val after = text.substringAfter(start)
        assertTrue("missing the end of: $start", after.contains(end))
        return after.substringBefore(end)
    }

    private fun source(relative: String): String {
        return TestSources.appFile("src/main/kotlin/io/panelassistant/android/$relative").readText()
    }
}
