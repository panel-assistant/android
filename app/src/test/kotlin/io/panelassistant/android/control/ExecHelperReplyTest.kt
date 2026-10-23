package io.panelassistant.android.control

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** A command completes after the ordinary helper socket's 500 ms reply deadline. */
@RunWith(Parameterized::class)
class ExecHelperReplyTest(private val operation: String) {
    @Test fun completedHelperCommandRemainsAuthoritative() {
        val commands = mutableListOf<String>()
        val helper = object : Daemon by FakeDaemon() {
            private fun answer(command: String, budgetMs: Long): String? {
                commands += command
                Thread.sleep(minOf(budgetMs, 650L))
                if (budgetMs < 650L) return null
                return when (command) {
                    "DENSITY" -> "PHYS=320 OVER=240"
                    "FONTSCALE" -> "SCALE=1.25"
                    "APPSTATE $PACKAGE" -> "FG"
                    else -> "OK"
                }
            }

            override fun send(cmd: String): String? = answer(cmd, 500L)
            override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult =
                answer(cmd, timeoutMs)?.let(DaemonLongResult::Reply) ?: DaemonLongResult.Indeterminate
        }
        val root = FakeRootShell(runResult = false)
        val env = FakeSystemEnv(directStartSucceeds = false)
        val system = SystemController(env, root, helper)
        val sizing = DensityController(canSu = false, root = root, daemon = helper)

        when (operation) {
            "start" -> assertTrue(system.launchAdminLauncher())
            "reload" -> assertTrue(system.reloadDashboard(PACKAGE))
            "home" -> assertTrue(system.setHomeActivity("$PACKAGE/.Home", PrivilegeRoute.DAEMON))
            "state" -> assertEquals(AppState.FG, system.dashboardState(PACKAGE))
            "density" -> assertTrue(sizing.set(240))
            "density reset" -> assertTrue(sizing.reset())
            "font" -> assertTrue(sizing.setFontScale(1.25f))
            "font reset" -> assertTrue(sizing.resetFontScale())
            "sizing observation" -> assertEquals(
                DisplaySizingObservation(240, 320, 1.25f), sizing.observeSizing(),
            )
            else -> error(operation)
        }
        assertEquals(if (operation == "sizing observation") 2 else 1, commands.size)
        assertTrue("a completed helper command must not be repeated through root", root.ran.isEmpty())
        assertTrue(root.outputRan.isEmpty())
        assertTrue(env.directStarts.isEmpty())
    }

    companion object {
        private const val PACKAGE = "com.example.dashboard"

        @JvmStatic @Parameterized.Parameters(name = "{0}") fun operations() = listOf(
            "start", "reload", "home", "state", "density", "density reset", "font", "font reset", "sizing observation",
        )
    }
}
