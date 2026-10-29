package io.panelassistant.android

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import io.panelassistant.android.control.AdaptiveBaselineCache
import io.panelassistant.android.control.AdaptiveBrightnessRange
import io.panelassistant.android.control.AmbientHistoryRuntime
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BaselineEstimate
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManualBrightnessAuthority
import io.panelassistant.android.control.ManualBrightnessPreferenceRecord
import io.panelassistant.android.control.ManualBrightnessPreferenceStore
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ln1p
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
        // Source-text reason: service lifecycle registrations cannot run in this JVM fixture without
        // Android sensor/runtime owners. Controller publication runs below; reconciliation itself is
        // exercised by AmbientThemeConsentTest.anAmbientVerdictReconcilesWithoutExplicitRetryConsent.
        val service = source("ServiceDisplay.kt")
        assertTrue("controller publication is connected to reconciliation", service.contains("onAmbientThemeChanged = ::reconcileAmbientTheme"))
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

    @Test fun `the controller judges smoothed light on the learned room scale`() {
        AmbientControllerRig().use { rig ->
            rig.sample(0, 100.0)
            assertEquals("100 lx is this room's dark endpoint", 0.0, rig.controller.status().ambientTheme.level!!, 1e-9)
            rig.sample(250, 10_000.0)
            val level = rig.controller.status().ambientTheme.level!!
            assertTrue("the model smooths a sudden change rather than judging raw lux: $level", level > 0.0 && level < 0.4)
            assertEquals("a transient bright sample has not changed the persisted verdict", false, rig.config.dashboardAmbientDark)
        }
    }

    @Test fun `a pending verdict is judged at its dwell deadline and published persisted off lock`() {
        AmbientControllerRig().use { rig ->
            rig.sample(0, 100.0)
            assertEquals(true, rig.controller.status().ambientTheme.pendingDark)
            assertEquals(false, rig.config.dashboardAmbientDark)
            assertTrue(rig.callbacks.isEmpty())

            // This earlier evaluation replaces the calm timer. Its successor must still fire at
            // the original dwell deadline, even if the room never supplies another sample.
            rig.sample(59_000, 1.0)
            assertEquals("remaining dwell wins over the calm interval", 60_000L, rig.scheduler.nextDeadline())
            rig.advance(59_999)
            assertEquals(false, rig.config.dashboardAmbientDark)
            assertTrue(rig.callbacks.isEmpty())
            rig.advance(60_000)
            assertEquals(true, rig.controller.status().ambientTheme.verdictDark)
            assertEquals(true, rig.config.dashboardAmbientDark)
            assertEquals("one callback, after persistence and outside the controller monitor", listOf(true to false), rig.callbacks)
            rig.advance(120_000)
            assertEquals("a stable verdict is not published twice", listOf(true to false), rig.callbacks)
        }
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

    @Test fun `the activity registers ambient inputs for status theme dispatch`() {
        // Source-text reason: DashboardRendererRig bypasses the Activity constructor, and the
        // mockable Android jar makes Activity.runOnUiThread a no-op. DashboardActivity is final,
        // so this fixture cannot override the dispatch. This pins only the
        // otherwise unexecutable preference-to-UI dispatch registration, not a redraw result.
        val listener = source("DashboardActivity.kt")
            .substringAfter("private val rendererPowerListener").substringBefore("convergeStatusTheme() }")
        assertTrue(listener.contains("key == \"dashboard_theme_ambient_dark\""))
        assertTrue(listener.contains("key == \"auto_brightness\""))
    }

    /** Real controller ticks, with only time, IO scheduling and baseline evidence supplied by the test. */
    private class AmbientControllerRig : AutoCloseable {
        var now = 0L
        val scheduler = ManualScheduler { now }
        private val historyScheduler = ManualScheduler { now }
        val config = Config(fakePreferences(mapOf(
            "auto_brightness" to true, "dashboard_theme_ambient_dark" to false,
        )).instance)
        val callbacks = mutableListOf<Pair<Boolean?, Boolean>>()
        lateinit var controller: AutoBrightnessController
            private set

        init {
            val context = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            }
            controller = AutoBrightnessController(
                context, BrightnessController(context, FakeRootShell(), FakeDaemon()), config,
                wallClockMs = { 1_700_000_000_000L + now }, elapsedRealtimeMs = { now },
                history = AmbientHistoryRuntime(context, executor = historyScheduler),
                preference = ManualBrightnessAuthority(object : ManualBrightnessPreferenceStore {
                    override fun load(): ManualBrightnessPreferenceRecord? = null
                    override fun save(record: ManualBrightnessPreferenceRecord) = Unit
                    override fun clear() = Unit
                }, wallClockMs = { 1_700_000_000_000L + now }, elapsedRealtimeMs = { now }, bootCount = { 1 }),
                baselineCache = AdaptiveBaselineCache { _, _, _, _, _ ->
                    BaselineEstimate(ln1p(100.0), 0.1, 0, 0,
                        AdaptiveBrightnessRange(ln1p(100.0), ln1p(10_000.0), 1.0))
                },
                canWriteBrightness = { false },
                onAmbientThemeChanged = { callbacks += config.dashboardAmbientDark to Thread.holdsLock(controller) },
            )
            // No production scheduler injection exists. Replace the idle executor before the first
            // public sensor input; ticks, rescheduling and callback dispatch remain production code.
            val field = AutoBrightnessController::class.java.getDeclaredField("scheduler").apply { isAccessible = true }
            (field.get(controller) as ScheduledThreadPoolExecutor).shutdownNow()
            field.set(controller, scheduler)
        }

        fun sample(at: Long, lux: Double) {
            now = at
            controller.submitPanelLux(lux)
            scheduler.runDue()
        }
        fun advance(at: Long) { now = at; scheduler.runDue() }
        override fun close() { controller.closeAndJoin(0); historyScheduler.shutdownNow(); scheduler.shutdownNow() }
    }

    private class ManualScheduler(private val clock: () -> Long) : ScheduledThreadPoolExecutor(1) {
        private val tasks = mutableListOf<Task>()
        private inner class Task(val at: Long, runnable: Runnable) : FutureTask<Unit>(runnable, Unit), ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit): Long = unit.convert(at - clock(), TimeUnit.MILLISECONDS)
            override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Task(clock() + unit.toMillis(delay), command).also { tasks += it }
        override fun execute(command: Runnable) { schedule(command, 0, TimeUnit.MILLISECONDS) }
        fun nextDeadline(): Long = tasks.filterNot { it.isDone }.minOf { it.at }
        fun runDue() {
            while (true) {
                val task = tasks.filter { !it.isDone && it.at <= clock() }.minByOrNull { it.at } ?: return
                task.run()
                task.get() // Surface uncaught task failures rather than silently swallowing them.
            }
        }
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
