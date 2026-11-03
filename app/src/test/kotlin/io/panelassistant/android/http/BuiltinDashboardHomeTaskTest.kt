package io.panelassistant.android.http

import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the built-in dashboard is the HOME app and Android started it from a HOME intent, it lives in the
 * HOME task, and an explicit start of its component opens a second dashboard in a task of its own above
 * it (a Sonoff NSPanel 86, 2026-10-15: stuck on "Preparing dashboard…" for 40 minutes). A settings change and
 * the service's startup after an in-place update must bring the HOME task forward instead.
 */
class BuiltinDashboardHomeTaskTest {
    private val own = "io.github.maxlyth.hapaneld"
    private val dashboardStart = "START $own/io.panelassistant.android.DashboardActivity"
    private val dashboardHome = ActivityRef(own, "io.panelassistant.android.DashboardActivity")
    private val env = FakeSystemEnv(ownPackage = own, default = dashboardHome)
    private val daemon = FakeDaemon(replies = mapOf(dashboardStart to "OK"))
    private var system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 27)

    private var homeTaskActivities = 0

    /** Android created a dashboard from a HOME intent and it is still alive, as DashboardActivity.onCreate reports. */
    private fun homeTaskDashboardAlive() {
        BuiltinDashboard.homeTaskActivityCreated()
        homeTaskActivities++
    }

    @After fun destroyHomeTaskDashboards() {
        repeat(homeTaskActivities) { BuiltinDashboard.homeTaskActivityDestroyed() }
    }

    @Test fun aThemeChangeBringsTheHomeTaskForward() = runBlocking {
        homeTaskDashboardAlive()
        changeTheme()
        assertEquals(1, env.homeStarts)
        assertEquals(emptyList<String>(), daemon.sent.filter { it.startsWith("START ") })
        assertEquals(emptyList<String>(), env.directStarts)
    }

    @Test fun theServiceStartupReconcileAfterAnUpdateBringsTheHomeTaskForward() {
        homeTaskDashboardAlive()
        val result = preparation().reconcileStartup(ensureHome = { _, _ -> }, launchHome = system::launchHome)
        assertEquals(RendererPreparationCoordinator.Result.ALREADY_READY, result)
        assertEquals(1, env.homeStarts)
        assertEquals(emptyList<String>(), daemon.sent.filter { it.startsWith("START ") })
    }

    /** Android 9 and older: a dashboard only ever started explicitly lives in an ordinary task, which the explicit start reaches. */
    @Test fun aDashboardStartedExplicitlyIsStillStartedExplicitly() = runBlocking {
        changeTheme()
        assertEquals(0, env.homeStarts)
        assertEquals(listOf(dashboardStart), daemon.sent.filter { it.startsWith("START ") })
    }

    /** Once Panel admin (or any other app) is HOME, a HOME intent would open it rather than the dashboard. */
    @Test fun anotherHomeAppKeepsTheExplicitStart() = runBlocking {
        homeTaskDashboardAlive()
        env.default = ActivityRef(own, "io.panelassistant.android.AdminLauncherActivity")
        changeTheme()
        assertEquals(0, env.homeStarts)
        assertEquals(listOf(dashboardStart), daemon.sent.filter { it.startsWith("START ") })
    }

    /** Android 10 and later: a dashboard the app starts while none is alive must be created in the HOME task,
     *  or Home builds a second one and reloads the page (bench Shelly X2i, 2026-11-03). */
    @Test fun android10StartsTheDashboardInTheHomeTaskWhenNoneIsAlive() {
        env.backgroundStartsAllowed = true
        system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 30)
        system.launchHome(SystemController.BUILTIN_DASHBOARD)
        assertEquals(1, env.homeStarts)
        assertEquals(emptyList<String>(), daemon.sent.filter { it.startsWith("START ") })
        assertEquals(emptyList<String>(), env.directStarts)
    }

    @Test fun android10SettingsChangeAlsoUsesTheHomeTask() = runBlocking {
        env.backgroundStartsAllowed = true
        system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 29)
        changeTheme()
        assertEquals(1, env.homeStarts)
        assertEquals(emptyList<String>(), daemon.sent.filter { it.startsWith("START ") })
    }

    /** Without the overlay grant Android drops a background HOME start silently; the privileged explicit start is not. */
    @Test fun android10WithoutBackgroundStartsKeepsTheExplicitStart() {
        system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 30)
        system.launchHome(SystemController.BUILTIN_DASHBOARD)
        assertEquals(0, env.homeStarts)
        assertEquals(listOf(dashboardStart), daemon.sent.filter { it.startsWith("START ") })
    }

    /** Android 15 lets an overlay-granted app start from the background only while its overlay shows. */
    @Test fun android15KeepsTheExplicitStart() {
        env.backgroundStartsAllowed = true
        system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 35)
        system.launchHome(SystemController.BUILTIN_DASHBOARD)
        assertEquals(0, env.homeStarts)
        assertEquals(listOf(dashboardStart), daemon.sent.filter { it.startsWith("START ") })
    }

    /** A visible activity of this app (MainActivity, the admin launcher's Dashboard tile) needs no grant. */
    @Test fun android10ForegroundCallersUseTheHomeIntent() {
        system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 30)
        assertTrue(system.builtinInHomeTask(foreground = true))
        assertFalse(system.builtinInHomeTask())
        env.default = ActivityRef(own, "io.panelassistant.android.AdminLauncherActivity")
        assertFalse(system.builtinInHomeTask(foreground = true))
    }

    private suspend fun changeTheme() = PaneldServerHttpFixture().use { fixture ->
        fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Theme panel")
        val result = transaction(fixture).applyAccepted(mapOf("dashboard_theme" to "Dark"))
        org.junit.Assert.assertTrue(result is ApplyAcceptedResult.Applied)
    }

    private fun preparation() = RendererPreparationCoordinator(
        builtinPackage = SystemController.BUILTIN_DASHBOARD,
        state = { RendererPreparationState(SystemController.BUILTIN_DASHBOARD, "http://ha.test") },
        borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
        persist = { true },
    )

    private fun transaction(fixture: PaneldServerHttpFixture) = AcceptedConfigTransaction(
        config = fixture.config,
        revisions = RevisionStore(fixture.directory),
        rendererPreparation = preparation(),
        system = system,
        values = ConfigValueProjection(
            config = fixture.config,
            configLiveValues = { emptyMap() },
            renderedLiveValues = { emptyMap() },
            pendingLiveSettings = { emptyMap() },
            stalledLiveSettings = { emptySet() },
            proximityJson = { "{}" },
            powerSafetyJson = { "{}" },
            haAreaCatalogJson = { "{}" },
        ),
        applySetting = { _, _ -> LiveSettingRequestOutcome.APPLIED },
        onEntityTargetChanged = {},
        setEntityLearningEnabled = { true },
        effectiveDashboardIsBuiltin = { true },
        requestTameReconcileAfterCommit = { true },
        snapInvalidate = {},
        onReconfigure = {},
    )
}
