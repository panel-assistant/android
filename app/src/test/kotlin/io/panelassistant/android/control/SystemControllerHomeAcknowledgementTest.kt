package io.panelassistant.android.control

import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemControllerHomeAcknowledgementTest {
    private val own = "io.github.maxlyth.hapaneld"
    private val dashboard = ActivityRef(own, "io.panelassistant.android.DashboardActivity")
    private val admin = ActivityRef(own, "io.panelassistant.android.AdminLauncherActivity")
    private val companion = ActivityRef("io.homeassistant.companion.android.minimal", "Home")
    private val vendor = ActivityRef("com.vendor.launcher", "Home")
    private val laterLauncher = ActivityRef("com.owner.launcher", "Home")

    /** A command may succeed without changing Android's resolved HOME. Acknowledgement must use
     * that read-back, just as AndroidSystemEnv does, rather than the actuator's return value. */
    private class HomeEnvironment(
        override val ownPackage: String,
        var home: ActivityRef,
        private val homes: List<ActivityRef>,
        var allowed: Boolean = true,
        var commandChangesHome: Boolean = true,
    ) : SystemEnv {
        var pending = true
        val acknowledgements = mutableListOf<String>()
        override fun isInstalled(pkg: String) = homes.any { it.pkg == pkg }
        override fun launchComponent(pkg: String): String? = null
        override fun homeActivities() = homes
        override fun defaultHome() = home
        override fun homeTakeoverAllowed() = allowed
        override fun homeTakeoverPending() = pending
        override fun directStart(component: String) = false
        override fun acknowledgeHomeTakeover(component: String) {
            acknowledgements += component
            if (defaultHome().component == component) pending = false
        }
    }

    private fun environment(home: ActivityRef = vendor) =
        HomeEnvironment(own, home, listOf(dashboard, admin, companion, vendor))

    private fun controller(env: HomeEnvironment): Pair<SystemController, FakeRootShell> {
        val root = FakeRootShell(onRun = { command ->
            val prefix = "cmd package set-home-activity "
            if (env.commandChangesHome && command.startsWith(prefix)) {
                env.home = env.homeActivities().single { it.component == command.removePrefix(prefix) }
            }
        })
        return SystemController(
            env, root, FakeDaemon(available = false),
            acquireHomeLock = { AutoCloseable {} },
        ) to root
    }

    private fun assertAcknowledged(env: HomeEnvironment, expected: ActivityRef) {
        assertEquals(listOf(expected.component), env.acknowledgements)
        assertFalse("confirmed HOME clears the pending claim", env.pending)
    }

    private fun assertLaterChoicePreserved(env: HomeEnvironment, controller: SystemController, root: FakeRootShell) {
        env.home = laterLauncher
        root.ran.clear()
        controller.ensureDashboardHome(SystemController.BUILTIN_DASHBOARD)
        assertEquals(laterLauncher, env.defaultHome())
        assertTrue("a later owner choice must not be reclaimed", root.ran.isEmpty())
    }

    @Test fun pendingBuiltinClaimAcknowledgesReadBackAndPreservesLaterChoice() {
        val env = environment()
        val (controller, root) = controller(env)
        controller.ensureDashboardHome(SystemController.BUILTIN_DASHBOARD)
        assertEquals(listOf("cmd package set-home-activity ${dashboard.component}"), root.ran)
        assertEquals(dashboard, env.defaultHome())
        assertAcknowledged(env, dashboard)
        assertLaterChoicePreserved(env, controller, root)
    }

    @Test fun admittedRouteAcknowledgesReadBackAndPreservesLaterChoice() {
        val env = environment()
        val (controller, root) = controller(env)
        assertTrue(controller.setHomeActivity(dashboard.component, PrivilegeRoute.SU))
        assertAcknowledged(env, dashboard)
        assertLaterChoicePreserved(env, controller, root)
    }

    @Test fun alreadyAdminHomeAcknowledgesWithoutActuation() {
        val env = environment(admin)
        val (controller, root) = controller(env)
        assertTrue(controller.ensureAdminLauncherHome())
        assertAcknowledged(env, admin)
        assertTrue(root.ran.isEmpty())
    }

    @Test fun alreadyCompanionHomeAcknowledgesAndPreservesLaterChoice() {
        val env = environment(companion)
        val (controller, root) = controller(env)
        controller.ensureDashboardHome(companion.pkg)
        assertAcknowledged(env, companion)
        assertTrue(root.ran.isEmpty())
        env.home = laterLauncher
        controller.ensureDashboardHome(companion.pkg)
        assertEquals(laterLauncher, env.defaultHome())
        assertTrue(root.ran.isEmpty())
    }

    @Test fun alreadyBuiltinHomeAcknowledgesAndPreservesLaterChoice() {
        val env = environment(dashboard)
        val (controller, root) = controller(env)
        controller.ensureDashboardHome(SystemController.BUILTIN_DASHBOARD)
        assertAcknowledged(env, dashboard)
        assertTrue(root.ran.isEmpty())
        assertLaterChoicePreserved(env, controller, root)
    }

    @Test fun acceptedCommandWithoutMatchingReadBackKeepsPendingClaim() {
        val env = environment().apply { commandChangesHome = false }
        val (controller, _) = controller(env)
        controller.ensureDashboardHome(SystemController.BUILTIN_DASHBOARD)
        assertEquals(listOf(dashboard.component), env.acknowledgements)
        assertEquals(vendor, env.defaultHome())
        assertTrue(env.pending)
    }

    @Test fun restoringHomeNeverAcknowledgesTakeoverOnEitherRoute() {
        for (route in listOf(null, PrivilegeRoute.SU)) {
            val env = environment(dashboard).apply { allowed = false }
            val (controller, _) = controller(env)
            assertTrue(controller.setHomeActivity(vendor.component, route, restoringHome = true))
            assertEquals(vendor, env.defaultHome())
            assertTrue(env.acknowledgements.isEmpty())
            assertTrue(env.pending)
        }
    }

    @Test fun incompleteSetupNeverAcknowledgesCorrectOrChangedHome() {
        for (home in listOf(admin, companion, dashboard, vendor)) {
            val env = environment(home).apply { allowed = false }
            val (controller, root) = controller(env)
            controller.ensureAdminLauncherHome()
            controller.ensureDashboardHome(companion.pkg)
            controller.ensureDashboardHome(SystemController.BUILTIN_DASHBOARD)
            assertFalse(controller.setHomeActivity(dashboard.component, PrivilegeRoute.SU))
            assertEquals(home, env.defaultHome())
            assertTrue(env.acknowledgements.isEmpty())
            assertTrue(env.pending)
            assertTrue(root.ran.isEmpty())
        }
    }
}
