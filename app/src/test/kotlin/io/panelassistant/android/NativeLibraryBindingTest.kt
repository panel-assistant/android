package io.panelassistant.android

import io.panelassistant.android.hardware.NativeLed
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.NativeHomeLock
import io.panelassistant.android.platform.PendingHomeResult
import io.panelassistant.android.platform.pendingHomeOperation
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Both JNI libraries bind to Kotlin classes by name: the wake-word library registers its natives on
 * a class path in JNI_OnLoad, and the LED library exports `Java_<package>_<class>_<method>` symbols.
 * A package or class rename that misses either side fails only when a panel loads the library, and
 * for the wake word silently (it reports unavailable). These load the binding sources, built for the
 * host by `buildHostJniLibraries`, against the real classes.
 */
class NativeLibraryBindingTest {
    private val directory = System.getProperty("hapaneld.test.hostJniDirectory")?.let(::File)

    @Test fun `the wake-word library registers every native on the wake-word class`() {
        // JNI_OnLoad fails, and System.load throws, if the class or any registered method is missing.
        System.load(library("libhapaneld_mww.so").absolutePath)
    }

    @Test fun `the LED library exports the natives the LED class declares`() {
        System.load(library("libhapaneld_led.so").absolutePath)
        // Throws UnsatisfiedLinkError if the symbol names do not match the class; the host has no LED.
        assertFalse(NativeLed.nativeProbe())
    }


    @Test(timeout = 10_000) fun `locked shell receipt requires the actual shared inode and current pending intent`() {
        System.load(library("libhapaneld_home.so").absolutePath)
        withShellLock { _, identity ->
            assertEquals(identity, NativeHomeLock.identity())
            val vendor = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")
            val config = Config(fakePreferences().instance).apply {
                setupEverCompleted = true
                check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
            }
            val env = FakeSystemEnv()
            fun eligibility(supplied: String) = pendingHomeOperation(
                config, env, null, claim = false, nativeAvailable = { false }, shellLockIdentity = supplied,
                setHome = { error("Eligibility must not mutate HOME") },
            )
            assertEquals(PendingHomeResult.SHELL_LOCKED, eligibility(identity))
            assertEquals(PendingHomeResult.DEFERRED, eligibility("0:0"))
            config.clearHandBackDesiredState()
            assertEquals(PendingHomeResult.DEFERRED, eligibility(identity))
        }
    }

    @Test(timeout = 10_000) fun `shell flock blocks real handback and retry works through an incumbent helper`() {
        System.load(library("libhapaneld_home.so").absolutePath)
        val root = FakeRootShell(available = false)
        val daemon = FakeDaemon(replies = mapOf("SETHOME com.vendor/.Home" to "OK"), onSend = {
            assertFalse(shellCanLock(), "helper submission must retain the native lock")
        })
        val controller = SystemController(FakeSystemEnv(), root, daemon)
        withShellLock { _, _ ->
            assertFalse(controller.setHomeActivity("com.vendor/.Home", restoringHome = true))
            assertTrue(daemon.sent.isEmpty())
            assertTrue(root.ran.isEmpty())
        }
        assertTrue(controller.setHomeActivity("com.vendor/.Home", restoringHome = true))
        assertEquals(listOf("SETHOME com.vendor/.Home"), daemon.sent)
        assertTrue(root.ran.isEmpty())
        assertTrue(shellCanLock(), "returned handback releases exclusion")
    }

    @Test(timeout = 10_000) fun `native exclusion covers su fallback and releases when the actuator throws`() {
        System.load(library("libhapaneld_home.so").absolutePath)
        val daemon = FakeDaemon(onSend = {
            assertFalse(shellCanLock(), "helper attempt must retain the native lock")
        })
        val root = FakeRootShell(onRun = {
            assertFalse(shellCanLock(), "SU fallback must retain the same native lock")
            throw IllegalStateException("actuator disconnected")
        })
        val controller = SystemController(FakeSystemEnv(), root, daemon)
        assertFailsWith<IllegalStateException> {
            controller.setHomeActivity("com.vendor/.Home", restoringHome = true)
        }
        assertEquals(listOf("SETHOME com.vendor/.Home"), daemon.sent)
        assertEquals(listOf("cmd package set-home-activity com.vendor/.Home"), root.ran)
        assertTrue(shellCanLock(), "exceptional completion must close the held description")
    }

    @Test(timeout = 10_000) fun `exited shell leaves no stale lock preventing handback`() {
        System.load(library("libhapaneld_home.so").absolutePath)
        val controller = SystemController(FakeSystemEnv(), FakeRootShell(), FakeDaemon(available = false))
        withShellLock { process, _ ->
            assertFalse(controller.setHomeActivity("com.vendor/.Home", restoringHome = true))
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertTrue(controller.setHomeActivity("com.vendor/.Home", restoringHome = true))
        }
    }

    /** The host build maps the production constant to this root-owned non-writable directory. */
    private fun withShellLock(block: (Process, String) -> Unit) {
        val process = ProcessBuilder("bash", "-c",
            "exec 9</usr/bin; flock -xn 9 || exit 70; stat -Lc '%d:%i' /proc/self/fd/9; read -r release",
        ).redirectErrorStream(true).start()
        try {
            val identity = checkNotNull(process.inputStream.bufferedReader().readLine())
            assertTrue(identity.matches(Regex("[0-9]+:[0-9]+")), identity)
            block(process, identity)
        } finally {
            if (process.isAlive) {
                process.outputStream.write("release\n".toByteArray())
                process.outputStream.flush()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            }
            process.destroyForcibly()
        }
    }

    private fun shellCanLock(): Boolean {
        val process = ProcessBuilder("bash", "-c", "exec 9</usr/bin; flock -xn 9").start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS))
            return when (val result = process.exitValue()) {
                0 -> true
                1 -> false
                else -> error("flock failed with exit $result")
            }
        } finally {
            process.destroyForcibly()
        }
    }

    private fun library(name: String): File {
        assumeTrue("host JNI libraries are built on Linux only", directory != null)
        return File(directory, name)
    }
}
