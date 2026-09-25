package io.github.maxlyth.hapaneld.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The navbar's volume receiver is registered on the service's context, so Android reports it as the
 * service's leak if it outlives onDestroy. closeAdmission unregisters it synchronously from onDestroy;
 * a mode apply still in flight on the mode worker must not register it again afterwards.
 */
class NavbarVolumeReceiverTest {
    private val registered = Collections.synchronizedList(mutableListOf<BroadcastReceiver?>())
    private val tempDir: File = Files.createTempDirectory("navbar-volume-receiver").toFile()

    @Test fun aClosedControllerRegistersNoVolumeReceiver() {
        val navbar = navbar()
        navbar.closeAdmission()

        setVolumeReceiver(navbar, enabled = true)

        assertEquals(0, registered.size)
    }

    @Test fun anOpenControllerRegistersTheVolumeReceiverOnce() {
        val navbar = navbar()

        setVolumeReceiver(navbar, enabled = true)
        setVolumeReceiver(navbar, enabled = true)

        assertEquals(1, registered.size)
    }

    private fun setVolumeReceiver(navbar: NavbarController, enabled: Boolean) {
        NavbarController::class.java.getDeclaredMethod("setVolumeReceiver", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(navbar, enabled)
    }

    private fun navbar() = NavbarController(
        context = RecordingContext(),
        system = allocate(SystemController::class.java),
        volume = allocate(VolumeController::class.java),
        brightness = allocate(BrightnessController::class.java),
        launcherPkg = { "" },
        dashboardPkg = { "" },
        appCanSu = false,
        hasRecents = false,
    )

    private inner class RecordingContext : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) {
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, _, _ -> null }
            } else null

        override fun getNoBackupFilesDir(): File = tempDir

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            registered += receiver
            return null
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? {
            registered += receiver
            return null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }
}
