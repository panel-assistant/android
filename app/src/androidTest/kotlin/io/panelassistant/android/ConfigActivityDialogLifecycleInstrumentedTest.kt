package io.panelassistant.android

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.panelassistant.android.control.CdpRelay
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the unchanged Activity entry point; this test also runs on the pre-fix production tree. */
@RunWith(AndroidJUnit4::class)
class ConfigActivityDialogLifecycleInstrumentedTest {
    @Test fun destroyingActivityDismissesItsShownHardenedModeResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "This lifecycle fixture must run on an emulator, never a physical panel",
            listOf("ro.kernel.qemu", "ro.boot.qemu").any {
                device.executeShellCommand("getprop $it").trim() == "1"
            },
        )
        val context = instrumentation.targetContext
        val config = Config(context)
        val originalMode = config.securityMode
        val marker = "result-window-${UUID.randomUUID()}"
        Log.i("ConfigDialogLifecycle", marker)

        // A real local listener prevents the real relay probe from claiming absence, so the
        // transition must return its verification-failed result before changing the security mode.
        ServerSocket(CdpRelay.PORT, 16, InetAddress.getByName("127.0.0.1")).use {
            ActivityScenario.launch(ConfigActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ConfigActivity::class.java.getDeclaredMethod("enableHardenedMode", Config::class.java)
                        .apply { isAccessible = true }
                        .invoke(activity, config)
                }
                assertTrue(
                    "The production transition must show its real failure dialog before destruction",
                    device.wait(
                        Until.hasObject(By.text(context.getString(R.string.unable_stop_remote_debugging))),
                        15_000,
                    ),
                )
                assertTrue(device.hasObject(By.text(context.getString(R.string.unable_stop_remote_debugging_detail))))
                // Closing while this window is visible is the original leak trigger.
            }
            instrumentation.waitForIdleSync()
            // ActivityThread closes leaked windows after onDestroy; let its cleanup/logging finish.
            SystemClock.sleep(500)
            instrumentation.waitForIdleSync()
            val logs = device.executeShellCommand("logcat -d -v brief --pid=${Process.myPid()}")
            val start = logs.lastIndexOf(marker)
            assertTrue("The test log marker must be retained so missing logs cannot pass", start >= 0)
            val trialLogs = logs.substring(start)
            assertFalse(
                "Destroyed ConfigActivity leaked its shown result window:\n$trialLogs",
                Regex("Activity .*ConfigActivity.*has leaked window").containsMatchIn(trialLogs),
            )
        }
        assertEquals("The refused transition must preserve security mode", originalMode, config.securityMode)
    }
}
