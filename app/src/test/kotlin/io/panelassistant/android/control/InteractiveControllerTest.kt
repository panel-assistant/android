package io.panelassistant.android.control

import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.RootShell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InteractiveControllerTest {
    private class Root(
        private val calls: MutableList<String>,
        runResults: List<Boolean> = emptyList(),
        byteResults: List<ByteArray?> = emptyList(),
    ) : RootShell {
        private val runs = runResults.toMutableList()
        private val bytes = byteResults.toMutableList()

        override fun available() = false
        override fun run(cmd: String): Boolean {
            calls += "su:$cmd"
            return if (runs.isEmpty()) false else runs.removeAt(0)
        }
        override fun runOutput(cmd: String): String? = null
        override fun runBytes(cmd: String): ByteArray? {
            calls += "su-bytes:$cmd"
            return if (bytes.isEmpty()) null else bytes.removeAt(0)
        }
        override fun fireAndForget(cmd: String) = false
    }

    private class Helper(
        private val calls: MutableList<String>,
        byteResults: List<ByteArray?> = emptyList(),
    ) : Daemon {
        private val bytes = byteResults.toMutableList()

        override fun available() = false
        override fun send(cmd: String): String? = null
        override fun sendLong(cmd: String, timeoutMs: Long) = DaemonLongResult.NotSubmitted
        override fun sendBytes(cmd: String): ByteArray? {
            calls += "helper-bytes:$cmd"
            return if (bytes.isEmpty()) null else bytes.removeAt(0)
        }
    }

    private class Accessibility(
        private val calls: MutableList<String>,
        results: List<Boolean> = emptyList(),
    ) : AccessibilityActions {
        private val remaining = results.toMutableList()

        override fun back() = result("a11y:back")
        override fun recents() = result("a11y:recents")
        override fun tap(x: Int, y: Int) = result("a11y:tap:$x:$y")

        private fun result(call: String): Boolean {
            calls += call
            return if (remaining.isEmpty()) false else remaining.removeAt(0)
        }
    }

    private fun controller(
        canSu: Boolean,
        calls: MutableList<String>,
        rootRuns: List<Boolean> = emptyList(),
        rootBytes: List<ByteArray?> = emptyList(),
        helperBytes: List<ByteArray?> = emptyList(),
        accessibilityResults: List<Boolean> = emptyList(),
    ) = InteractiveController(
        canSu = canSu,
        root = Root(calls, rootRuns, rootBytes),
        daemon = Helper(calls, helperBytes),
        accessibility = Accessibility(calls, accessibilityResults),
    )

    @Test fun screenshotPrefersSuAndStopsOnNonemptyBytes() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val result = controller(
            canSu = true,
            calls = calls,
            rootBytes = listOf(png),
            helperBytes = listOf(byteArrayOf(1)),
        ).screenshot()

        assertArrayEquals(png, result)
        assertEquals(listOf("su-bytes:screencap -p"), calls)
    }

    @Test fun screenshotFallsFromEmptySuBytesToHelper() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(1, 2, 3)
        val result = controller(
            canSu = true,
            calls = calls,
            rootBytes = listOf(byteArrayOf()),
            helperBytes = listOf(png),
        ).screenshot()

        assertArrayEquals(png, result)
        assertEquals(listOf("su-bytes:screencap -p", "helper-bytes:SCREENCAP"), calls)
    }

    @Test fun screenshotFallsFromRawHelperEofToSu() {
        val calls = mutableListOf<String>()
        val png = byteArrayOf(4, 5, 6)
        val result = controller(
            canSu = false,
            calls = calls,
            rootBytes = listOf(png),
            helperBytes = listOf(byteArrayOf()),
        ).screenshot()

        assertArrayEquals(png, result)
        assertEquals(listOf("helper-bytes:SCREENCAP", "su-bytes:screencap -p"), calls)
    }

    @Test fun screenshotReturnsNullAfterBothByteRoutesFail() {
        val calls = mutableListOf<String>()
        assertNull(controller(canSu = false, calls = calls).screenshot())
        assertEquals(listOf("helper-bytes:SCREENCAP", "su-bytes:screencap -p"), calls)
    }

    @Test fun concurrentScreenshotsAreSingleFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val root = object : RootShell {
            override fun available() = true
            override fun run(cmd: String) = false
            override fun runOutput(cmd: String): String? = null
            override fun runBytes(cmd: String): ByteArray? {
                invocations.incrementAndGet()
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                return byteArrayOf(1, 2, 3)
            }
            override fun fireAndForget(cmd: String) = false
        }
        val controller = InteractiveController(
            canSu = true,
            root = root,
            daemon = Helper(mutableListOf()),
            accessibility = Accessibility(mutableListOf()),
        )
        var first: ByteArray? = null
        val worker = Thread { first = controller.screenshot() }.apply { start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertNull(controller.screenshot())
        assertEquals(1, invocations.get())
        release.countDown()
        worker.join(2_000)
        assertArrayEquals(byteArrayOf(1, 2, 3), first)
    }

    @Test fun postTapScreenshotCanWaitBehindTheCaptureAlreadyInFlight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val root = object : RootShell {
            override fun available() = true
            override fun run(cmd: String) = false
            override fun runOutput(cmd: String): String? = null
            override fun runBytes(cmd: String): ByteArray? {
                val invocation = invocations.incrementAndGet()
                if (invocation == 1) {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                return byteArrayOf(invocation.toByte())
            }
            override fun fireAndForget(cmd: String) = false
        }
        val controller = InteractiveController(
            canSu = true,
            root = root,
            daemon = Helper(mutableListOf()),
            accessibility = Accessibility(mutableListOf()),
        )
        val first = Thread { controller.screenshot() }.apply { start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        var waited: RoutedValue<ByteArray>? = null
        val second = Thread { waited = controller.screenshotWithRoute(2_000) }.apply { start() }
        Thread.sleep(25)
        assertTrue("the post-tap capture must wait rather than barge or fail", second.isAlive)
        release.countDown()
        first.join(2_000)
        second.join(2_000)
        assertEquals(2, invocations.get())
        assertEquals(PrivilegeRoute.SU, waited?.route)
        assertArrayEquals(byteArrayOf(2), waited?.value)
    }

    @Test fun backFallsFromPreferredSuToAccessibility() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = true,
            calls = calls,
            rootRuns = listOf(false),
            accessibilityResults = listOf(true),
        ).back())
        assertEquals(listOf("su:input keyevent 4", "a11y:back"), calls)
    }

    @Test fun recentsFallsFromPreferredAccessibilityToSu() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(false),
        ).recents())
        assertEquals(listOf("a11y:recents", "su:input keyevent 187"), calls)
    }

    @Test fun navigationStopsAfterPreferredRouteSucceeds() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(true),
        ).back())
        assertEquals(listOf("a11y:back"), calls)
    }

    @Test fun navigationReportsAllRoutesFailed() {
        val calls = mutableListOf<String>()
        assertFalse(controller(canSu = true, calls = calls).back())
        assertEquals(listOf("su:input keyevent 4", "a11y:back"), calls)
    }

    @Test fun tapFallsAcrossRoutesAndUsesIntegralCoordinates() {
        val calls = mutableListOf<String>()
        assertTrue(controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(false),
        ).tap(12.9f, 34.1f))
        assertEquals(listOf("a11y:tap:12:34", "su:input tap 12 34"), calls)
    }

    @Test fun tapReportsTheRouteThatActuallyCompleted() {
        val calls = mutableListOf<String>()
        val route = controller(
            canSu = false,
            calls = calls,
            accessibilityResults = listOf(true),
        ).tapWithRoute(12f, 34f)
        assertEquals(PrivilegeRoute.ACCESSIBILITY, route)
        assertEquals(listOf("a11y:tap:12:34"), calls)
    }

    @Test fun combinedTapUsesExactlyOneSelectedRouteAndNeverFallsThrough() {
        val calls = mutableListOf<String>()
        val route = controller(
            canSu = false,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(false),
        ).tapOnceWithRoute(12f, 34f)

        assertNull(route)
        assertEquals(listOf("a11y:tap:12:34"), calls)
    }

    @Test fun combinedTapUsesOneRootCommandAttemptOnRootProfiles() {
        val calls = mutableListOf<String>()
        val route = controller(
            canSu = true,
            calls = calls,
            rootRuns = listOf(true),
            accessibilityResults = listOf(true),
        ).tapOnceWithRoute(12f, 34f)

        assertEquals(PrivilegeRoute.SU, route)
        assertEquals(listOf("su:input tap 12 34"), calls)
    }

    @Test fun tapRejectsInvalidCoordinatesBeforeEitherBoundary() {
        listOf(
            Float.NaN to 1f,
            Float.POSITIVE_INFINITY to 1f,
            -1f to 1f,
            1f to -1f,
        ).forEach { (x, y) ->
            val calls = mutableListOf<String>()
            assertFalse(controller(canSu = true, calls = calls).tap(x, y))
            assertTrue(calls.isEmpty())
        }
    }

    @Test fun screenshotReportsTheRouteThatProducedBytes() {
        val calls = mutableListOf<String>()
        val result = controller(
            canSu = false,
            calls = calls,
            helperBytes = listOf(byteArrayOf(1, 2, 3)),
        ).screenshotWithRoute()
        assertEquals(PrivilegeRoute.DAEMON, result?.route)
        assertArrayEquals(byteArrayOf(1, 2, 3), result?.value)
    }

    @Test fun combinedScreenshotUsesExactlyOneSelectedRoute() {
        val calls = mutableListOf<String>()
        val result = InteractiveController(
            canSu = false,
            root = Root(calls, byteResults = listOf(byteArrayOf(9))),
            daemon = Helper(calls, byteResults = listOf(null)),
            accessibility = Accessibility(calls),
        ).screenshotOnceWithRoute(0)

        assertNull(result)
        assertEquals(listOf("helper-bytes:SCREENCAP"), calls)
    }

    @Test fun failedAccessibilityFallsThroughToSpeculativeSu() {
        val calls = mutableListOf<String>()
        assertTrue(controller(canSu = false, calls = calls, rootRuns = listOf(true)).back())
        assertEquals(listOf("a11y:back", "su:input keyevent 4"), calls)
    }
}
