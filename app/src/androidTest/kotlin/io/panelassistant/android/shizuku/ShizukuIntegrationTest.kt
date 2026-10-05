package io.panelassistant.android.shizuku

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Real Binder/UID-2000 smoke. Runs only in the dedicated emulator workflow, never on fleet panels. */
@RunWith(AndroidJUnit4::class)
class ShizukuIntegrationTest {
    @Test fun typedShellOperationsPackageStreamAndManagerRearm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        ShizukuConsent.enable(context)
        ShizukuBridge.refresh(requestPermission = true)
        val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i).*allow.*"))), 15_000)
        allow?.click()
        waitUntil(20_000) { ShizukuBridge.available() }

        assertTrue("Shizuku UserService did not become ready", ShizukuBridge.available())
        assertEquals(ShizukuPolicy.SHELL_UID, ShizukuBridge.uid())
        val png = ShizukuBridge.screenshot()
        assertNotNull(png)
        assertTrue("screenshot should have a PNG signature", png!!.size > 8 && png[1] == 'P'.code.toByte())
        val densityReply = ShizukuBridge.density()
        assertNotNull(densityReply)
        val densityBefore = densityReply!!
        val densityValueBefore = densityValues(densityBefore).last()
        val densityTest = if (densityValueBefore == 200) 220 else 200
        try {
            assertTrue(ShizukuBridge.setDensity(densityTest))
            assertEquals(densityTest, densityValues(requireNotNull(ShizukuBridge.density())).last())
        } finally {
            if (densityValues(densityBefore).size > 1) ShizukuBridge.setDensity(densityValueBefore)
            else ShizukuBridge.resetDensity()
        }
        assertEquals(densityValueBefore, densityValues(requireNotNull(ShizukuBridge.density())).last())

        val fontBefore = requireNotNull(ShizukuBridge.fontScale()).trim().toFloat()
        val fontTest = if (fontBefore == 1.1f) 1.2f else 1.1f
        try {
            assertTrue(ShizukuBridge.setFontScale(fontTest))
            assertEquals(fontTest, requireNotNull(ShizukuBridge.fontScale()).trim().toFloat(), 0.001f)
        } finally {
            ShizukuBridge.setFontScale(fontBefore)
        }
        assertEquals(fontBefore, requireNotNull(ShizukuBridge.fontScale()).trim().toFloat(), 0.001f)
        assertTrue(ShizukuBridge.tap(1, 1))

        // The workflow copies the exact pinned manager APK into target app-private storage. Reinstalling
        // the same package exercises the typed APK stream without pretending to prove a real manager
        // upgrade or rollback. ha-paneld deliberately does not auto-update Shizuku in this release.
        val apkPath = InstrumentationRegistry.getArguments().getString("managerApk")
        assertNotNull("managerApk instrumentation argument missing", apkPath)
        val apk = File(apkPath!!)
        assertTrue("manager APK fixture missing", apk.isFile)
        val install = ShizukuBridge.installApk(apk, allowDowngrade = false, timeoutMs = 180_000)
        assertTrue("manager reinstall failed: $install", install?.contains("Success", ignoreCase = true) == true)
        assertEquals(ShizukuManagerIdentity.Status.TRUSTED, ShizukuManagerIdentity.status(context))

        // Package replacement may stop the manager. Rearm it through the documented ADB-shell script,
        // then require a fresh trusted binding rather than accepting a stale pre-install remote.
        ShizukuBridge.refresh()
        if (ShizukuBridge.uid() != ShizukuPolicy.SHELL_UID) {
            device.executeShellCommand(
                "sh /storage/emulated/0/Android/data/${ShizukuManagerIdentity.PACKAGE}/start.sh",
            )
        }
        waitUntil(20_000) {
            ShizukuBridge.refresh()
            ShizukuBridge.uid() == ShizukuPolicy.SHELL_UID
        }
        assertEquals(ShizukuPolicy.SHELL_UID, ShizukuBridge.uid())
    }

    private fun densityValues(reply: String): List<Int> =
        Regex("density:\\s*(\\d+)").findAll(reply).map { it.groupValues[1].toInt() }.toList()
            .also { require(it.isNotEmpty()) { "unparseable density: $reply" } }

    private fun waitUntil(timeoutMs: Long, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
    }
}
