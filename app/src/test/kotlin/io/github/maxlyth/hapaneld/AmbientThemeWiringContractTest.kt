package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.DashboardTheme
import java.io.File
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
        val service = source("PaneldService.kt")
        val registration = service.substringAfter("BuiltinDashboard.setForegroundGainedListener {")
            .substringBefore("\n            }\n")
        assertTrue(registration.contains("reconcileAmbientTheme()"))
        assertTrue("teardown must clear it", service.contains("BuiltinDashboard.setForegroundGainedListener(null)"))
        val reconcile = service.substringAfter("private fun reconcileAmbientTheme()").substringBefore("\n    }\n")
        assertTrue(reconcile.contains("if (applied == effective) ambientRebuildRequestedFor = null"))
        assertTrue(reconcile.contains("alreadyRequested = ambientRebuildRequestedFor"))
        val dashboard = source("control/BuiltinDashboard.kt")
        assertTrue(dashboard.contains("if (changed && value) foregroundGainedListener?.invoke()"))
    }

    // --- source wiring ------------------------------------------------------------------------------

    @Test fun `the controller judges the model's own output on the room's own scale`() {
        val tick = source("control/AutoBrightnessController.kt")
            .substringAfter("private fun tick(force: Boolean): Long? {")
            .substringBefore("private fun publishAmbientVerdict()")
        assertTrue(
            tick.contains(
                "lastAmbientLevel = AdaptiveLuxCurve.normalizedLevel(result.effectiveLux, result.estimate.brightnessRange)",
            ),
        )
        assertTrue(tick.contains("if (ambientTheme.observe(nowElapsed, lastAmbientLevel)) publishAmbientVerdict()"))
        assertTrue("a pending change is judged when its dwell ends", tick.contains("ambientTheme.pendingDeadlineMs()"))
    }

    @Test fun `a verdict is persisted and handed to the service off the controller's lock`() {
        val publish = source("control/AutoBrightnessController.kt")
            .substringAfter("private fun publishAmbientVerdict() {").substringBefore("\n    }\n")
        assertTrue(publish.contains("config.setDashboardAmbientDark(dark)"))
        assertTrue(publish.contains("scheduler.execute { if (!closed) onAmbientThemeChanged() }"))
        val service = source("PaneldService.kt")
        assertTrue(service.contains("onAmbientThemeChanged = ::reconcileAmbientTheme"))
        assertTrue(service.contains("private fun reconcileAmbientTheme(): Unit = synchronized(ambientRebuildLock) {"))
        val reconcile = service.substringAfter("private fun reconcileAmbientTheme()").substringBefore("\n    }\n")
        assertTrue(reconcile.contains("system.reloadDashboard(SystemController.BUILTIN_DASHBOARD"))
        assertTrue(reconcile.contains("val applied = BuiltinDashboard.appliedThemeSignature"))
        assertTrue(reconcile.contains("appliedSignature = applied,"))
    }

    @Test fun `native screens follow the effective theme too`() {
        val darkFor = source("StatusSurface.kt").substringAfter("fun darkFor(").substringBefore("\n        )\n")
        assertTrue(darkFor.contains("DashboardTheme.forcedDark(config.dashboardThemeEffective)"))
    }

    @Test fun `the ambient verdict and auto-brightness redraw the status surfaces`() {
        val listener = source("DashboardActivity.kt")
            .substringAfter("private val rendererPowerListener").substringBefore("convergeStatusTheme() }")
        assertTrue(listener.contains("key == \"dashboard_theme_ambient_dark\""))
        assertTrue(listener.contains("key == \"auto_brightness\""))
    }

    private fun source(relative: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(
            File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
            File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$relative"),
        ).first(File::isFile).readText()
    }
}
