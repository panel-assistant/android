package io.panelassistant.android.control

import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.testsupport.scratchDirectory

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A panel moved from the app's earlier package id can still carry that app's Always on crop: Android
 * keeps `wm overscan` after the app that set it is uninstalled, and this app holds no marker for it.
 * Choosing any mode other than Always on must take the crop away, or the band stays under every mode.
 */
class NavbarUnownedCropTest {
    private val tempDir: File = scratchDirectory("navbar-unowned-crop").toFile()
    private val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var answering = true

    @Before @After fun reset() {
        BuiltinDashboard.setNavbarRevealHandler(null)
    }

    @Test fun offAndSwipeZeroACropThisAppNeverRecorded() {
        val navbar = navbar(appCanSu = true)

        navbar.switchTo(NavbarController.MODE_OFF)
        navbar.switchTo(NavbarController.MODE_SWIPE)

        assertEquals("one zero crop for the process, not one per mode change", listOf(ZERO_CROP), crops())
    }

    @Test fun rootThatWasNotReadyIsAskedAgainAtTheNextModeChange() {
        answering = false
        val navbar = navbar(appCanSu = true)
        navbar.switchTo(NavbarController.MODE_OFF)

        commands.clear()
        answering = true
        navbar.switchTo(NavbarController.MODE_SWIPE)
        navbar.switchTo(NavbarController.MODE_OFF)

        assertEquals("cleared once root answers, then not again", listOf(ZERO_CROP), crops())
    }

    @Test fun aPanelWithoutRootRunsNoCropCommand() {
        val navbar = navbar(appCanSu = false)

        navbar.switchTo(NavbarController.MODE_SWIPE)

        assertEquals(emptyList<String>(), commands.toList())
    }

    private fun NavbarController.switchTo(mode: String) = applyAsync(mode).get(10, TimeUnit.SECONDS).status

    private fun crops() = commands.filter { it.startsWith("wm overscan") }.map { it.substringBefore(" 2>&1") }

    private fun navbar(appCanSu: Boolean) = NavbarController(
        context = RecordingContext(),
        system = allocate(SystemController::class.java),
        volume = allocate(VolumeController::class.java),
        brightness = allocate(BrightnessController::class.java),
        launcherPkg = { "" },
        dashboardPkg = { "" },
        appCanSu = appCanSu,
        hasRecents = false,
        overlayHeld = { true },
        overscanRoot = RecordingRoot(),
    )

    /** Answers the crop command as Android 8.1 does (it exists and succeeds), or not at all. */
    private inner class RecordingRoot : RootShell {
        override fun available() = answering
        override fun run(cmd: String): Boolean { commands += cmd; return answering }
        override fun runOutput(cmd: String): String? {
            commands += cmd
            if (!answering) return null
            return "${NavbarOverscanRecovery.RC_MARKER}=0\n"
        }
        override fun runBytes(cmd: String): ByteArray? = null
    }

    private inner class RecordingContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) {
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, _, _ -> null }
            } else null

        override fun getNoBackupFilesDir(): File = tempDir

        override fun getPackageName(): String = "io.panelassistant.android"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private companion object {
        const val ZERO_CROP = "wm overscan 0,0,0,0"
    }
}
