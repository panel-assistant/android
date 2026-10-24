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

    /** A dashboard only ever started explicitly lives in an ordinary task, which the explicit start reaches. */
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

    /** From Android 10 a background HOME start can be dropped silently; the privileged explicit start is not. */
    @Test fun android10AndNewerKeepTheExplicitStart() = runBlocking {
        homeTaskDashboardAlive()
        system = SystemController(env, FakeRootShell(runResult = false), daemon, sdkInt = 29)
        changeTheme()
        assertEquals(0, env.homeStarts)
        assertEquals(listOf(dashboardStart), daemon.sent.filter { it.startsWith("START ") })
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
