package io.panelassistant.android.control

import io.panelassistant.android.testsupport.scratchDirectory

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * android #211: in Swipe reveal mode the bottom-edge strip sat above the on-screen keyboard (measured on a Sonoff
 * panel, Android 8.1: strip layer 121005, keyboard 22005), so it caught the keyboard's bottom row and re-injected
 * each tap through root, late and out of order. A window that is not focusable is ordered above the input method
 * unless it also carries FLAG_ALT_FOCUSABLE_IM, which places it behind the keyboard. The strip must carry both.
 */
class NavbarStripUnderKeyboardTest {
    private val added = CopyOnWriteArrayList<WindowManager.LayoutParams>()
    private val tempDir: File = scratchDirectory("navbar-strip-keyboard").toFile()

    @Before @After fun reset() {
        BuiltinDashboard.setNavbarRevealHandler(null)
    }

    @Test fun theSwipeRevealStripIsOrderedBehindTheKeyboard() {
        val navbar = NavbarController(
            context = RecordingContext(),
            system = allocate(SystemController::class.java),
            volume = allocate(VolumeController::class.java),
            brightness = allocate(BrightnessController::class.java),
            launcherPkg = { "" },
            dashboardPkg = { "" },
            appCanSu = false,
            hasRecents = false,
            overlayHeld = { true },
            postModeToMain = { it.run(); true },
        )

        val outcome = navbar.applyAsync(NavbarController.MODE_SWIPE).get(10, TimeUnit.SECONDS)
        assertEquals(NavbarModeApplyStatus.APPLIED, outcome.status)

        assertEquals("Swipe reveal outside the dashboard adds exactly the strip", 1, added.size)
        val behindKeyboard = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        assertTrue(
            "strip flags 0x${Integer.toHexString(added[0].flags)} must include NOT_FOCUSABLE and ALT_FOCUSABLE_IM",
            added[0].flags and behindKeyboard == behindKeyboard,
        )
    }

    private inner class RecordingContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) {
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
                    if (method.name == "addView") added += args[1] as WindowManager.LayoutParams
                    null
                }
            } else null

        override fun getNoBackupFilesDir(): File = tempDir

        @Suppress("DEPRECATION")
        override fun getResources(): Resources = object : Resources(null, null, null) {
            override fun getDisplayMetrics() = DisplayMetrics().apply { density = 1f; widthPixels = 480; heightPixels = 480 }
        }

        override fun getPackageName(): String = "io.panelassistant.android"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
