package io.panelassistant.android.control

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import io.panelassistant.android.camera.CameraIndicator
import io.panelassistant.android.Config
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app turns its own "Display over other apps" on through root only while Android shows no decision
 * for it. An owner who turned it off keeps it off: deny, ignore, foreground and errored are never
 * overwritten, and neither is an answer that cannot be read. The navbar, the kiosk unlock corner and
 * the camera indicator all take that one rule.
 */
class OwnOverlayPermissionTest {
    private val tempDir: File = scratchDirectory("own-overlay-permission").toFile()

    @Before @After fun reset() {
        BuiltinDashboard.setNavbarRevealHandler(null)
    }

    @Test fun anOwnersChoiceIsKept() {
        for (answer in listOf(
            "SYSTEM_ALERT_WINDOW: deny",
            "SYSTEM_ALERT_WINDOW: ignore",
            "SYSTEM_ALERT_WINDOW: foreground",
            "SYSTEM_ALERT_WINDOW: errored",
            "SYSTEM_ALERT_WINDOW: deny; rejectTime=+5m2s ago\n",
            "SYSTEM_ALERT_WINDOW: allow",
            "No operations.\nDefault mode: allow",
            // One line set, one not: not proven unset.
            "Uid mode: SYSTEM_ALERT_WINDOW: deny\nSYSTEM_ALERT_WINDOW: default",
            "SYSTEM_ALERT_WINDOW: default\nSYSTEM_ALERT_WINDOW: deny",
        )) {
            val root = FakeRoot(answer)
            assertFalse(answer, OwnOverlayPermission.ensure(PKG, root, { false }, "test"))
            assertEquals(answer, listOf(GET), root.commands)
        }
    }

    @Test fun anUnreadableAnswerIsNeverAReasonToWrite() {
        for (answer in listOf(null, "", "\n", "Error: no such op", "No operations.\nDefault mode: bogus", "default")) {
            val root = FakeRoot(answer)
            assertFalse("$answer", OwnOverlayPermission.ensure(PKG, root, { false }, "test"))
            assertEquals("$answer", listOf(GET), root.commands)
        }
    }

    @Test fun anUnsetOpIsAllowed() {
        for (answer in listOf(
            "SYSTEM_ALERT_WINDOW: default",
            "SYSTEM_ALERT_WINDOW: default\n",
            "SYSTEM_ALERT_WINDOW: default; rejectTime=+1h2m ago",
            "No operations.",
            "No operations.\n",
            // Android 14 reports an unset op with the platform default; nobody chose it for this app.
            "No operations.\nDefault mode: default",
            "No operations.\nDefault mode: deny",
            "No operations.\nDefault mode: ignore",
            "No operations.\nDefault mode: foreground",
            "No operations.\nDefault mode: errored\n",
        )) {
            var held = false
            val root = FakeRoot(answer, onSet = { held = true })
            assertTrue(answer, OwnOverlayPermission.ensure(PKG, root, { held }, "test"))
            assertEquals(answer, listOf(GET, SET), root.commands)
        }
    }

    @Test fun aHeldOverlayRunsNothing() {
        val root = FakeRoot("SYSTEM_ALERT_WINDOW: default")
        assertTrue(OwnOverlayPermission.ensure(PKG, root, { true }, "test"))
        assertEquals(emptyList<String>(), root.commands)
    }

    @Test fun theNavbarKeepsAnOwnersDenyAndAllowsAnUnsetOp() {
        val denied = FakeRoot("SYSTEM_ALERT_WINDOW: deny")
        navbar(denied).applyAsync(NavbarController.MODE_SWIPE).get(10, TimeUnit.SECONDS)
        assertEquals(listOf(GET), denied.commands)

        val unset = FakeRoot("No operations.\nDefault mode: default")
        navbar(unset).applyAsync(NavbarController.MODE_SWIPE).get(10, TimeUnit.SECONDS)
        assertEquals(listOf(GET, SET), unset.commands)
    }

    @Test fun theKioskKeepsAnOwnersDenyAndAllowsAnUnsetOp() {
        val denied = FakeRoot("SYSTEM_ALERT_WINDOW: ignore")
        lockAndUnlock(denied)
        assertEquals(listOf(GET), denied.commands.filter { "appops" in it })

        val unset = FakeRoot("SYSTEM_ALERT_WINDOW: default")
        lockAndUnlock(unset)
        assertEquals(listOf(GET, SET), unset.commands.filter { "appops" in it })
    }

    @Test fun theCameraIndicatorKeepsAnOwnersDenyAndAllowsAnUnsetOp() {
        val denied = FakeRoot("SYSTEM_ALERT_WINDOW: foreground")
        assertFalse(cameraIndicator(denied).show())
        assertEquals(listOf(GET), denied.commands)

        val unset = FakeRoot("SYSTEM_ALERT_WINDOW: default")
        assertFalse("the fake overlay stays unheld", cameraIndicator(unset).show())
        assertEquals(listOf(GET, SET), unset.commands)
    }

    private fun navbar(root: RootShell) = NavbarController(
        context = TestContext(),
        system = allocate(SystemController::class.java),
        volume = allocate(VolumeController::class.java),
        brightness = allocate(BrightnessController::class.java),
        launcherPkg = { "" },
        dashboardPkg = { "" },
        appCanSu = false,
        hasRecents = false,
        overlayHeld = { false },
        overscanRoot = FakeRoot(null),
        overlayRoot = root,
    )

    private fun lockAndUnlock(root: FakeRoot) {
        val kiosk = KioskController(
            TestContext(),
            allocate(SystemController::class.java),
            allocate(Config::class.java),
            persistentPolicyEligible = true,
            root = root,
            overlayHeld = { false },
        )
        try {
            assertTrue(kiosk.apply(true))
        } finally {
            kiosk.apply(false)
        }
    }

    private fun cameraIndicator(root: RootShell) = CameraIndicator(
        context = TestContext(),
        ledEffect = allocate(LedEffectController::class.java),
        restoreLed = {},
        screenOff = { false },
        root = root,
        overlayHeld = { false },
    )

    private class FakeRoot(private val answer: String?, private val onSet: () -> Unit = {}) : RootShell {
        val commands = mutableListOf<String>()
        override fun available(): Boolean = true
        override fun run(cmd: String): Boolean {
            commands += cmd
            if (cmd == SET) onSet()
            return true
        }
        override fun runOutput(cmd: String): String? {
            commands += cmd
            return when (cmd) {
                GET -> answer
                KioskPolicyRecovery.IMMERSIVE_READBACK -> "null"
                else -> null
            }
        }
        override fun runBytes(cmd: String): ByteArray? = null
    }

    private inner class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) {
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, _, _ -> null }
            } else null
        override fun getNoBackupFilesDir(): File = tempDir
        override fun getPackageName(): String = PKG
        override fun startActivity(intent: Intent?) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object {
        const val PKG = "io.panelassistant.android"
        const val GET = "appops get $PKG SYSTEM_ALERT_WINDOW 2>/dev/null"
        const val SET = "appops set $PKG SYSTEM_ALERT_WINDOW allow"
    }
}
