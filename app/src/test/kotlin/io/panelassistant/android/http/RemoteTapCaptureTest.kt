package io.panelassistant.android.http

import io.panelassistant.android.control.PrivilegeRoute
import io.panelassistant.android.control.RoutedValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTapCaptureTest {
    private fun execution(
        executeBefore: Long = 1_000,
        completeBefore: Long = 2_000,
        settleMs: Long = 150,
    ) = TapCaptureExecution(
        x = 321.5f,
        y = 654.25f,
        loopback = false,
        executeBeforeElapsedMs = executeBefore,
        completeBeforeElapsedMs = completeBefore,
        settleMs = settleMs,
        maxScreenshotWaitMs = 10_000,
    )

    @Test fun `success injects exactly one tap then settles and captures exactly once`() {
        var now = 100L
        val events = mutableListOf<String>()
        val png = byteArrayOf(1, 2, 3)

        val result = performTapCapture(
            execution(),
            hardened = { false },
            nowElapsedMs = { now },
            tap = { x, y ->
                events += "tap:$x,$y"
                PrivilegeRoute.ACCESSIBILITY
            },
            settle = { delay ->
                events += "settle:$delay"
                now += delay
                true
            },
            screenshot = { wait ->
                events += "screenshot:$wait"
                RoutedValue(PrivilegeRoute.DAEMON, png)
            },
        )

        assertEquals(listOf("tap:321.5,654.25", "settle:150", "screenshot:1750"), events)
        assertTrue(result is TapCaptureResult.Success)
        result as TapCaptureResult.Success
        assertEquals(PrivilegeRoute.ACCESSIBILITY, result.inputRoute)
        assertEquals(PrivilegeRoute.DAEMON, result.screenshotRoute)
        assertArrayEquals(png, result.png)
    }

    @Test fun `expired queued work and hardened execution never inject a tap`() {
        var taps = 0
        val tap = { _: Float, _: Float -> taps += 1; PrivilegeRoute.SU }

        val expired = performTapCapture(
            execution(executeBefore = 100),
            hardened = { false },
            nowElapsedMs = { 100 },
            tap = tap,
            settle = { true },
            screenshot = { RoutedValue(PrivilegeRoute.SU, byteArrayOf()) },
        )
        val hardened = performTapCapture(
            execution(),
            hardened = { true },
            nowElapsedMs = { 100 },
            tap = tap,
            settle = { true },
            screenshot = { RoutedValue(PrivilegeRoute.SU, byteArrayOf()) },
        )

        assertEquals(TapCaptureResult.Expired, expired)
        assertEquals(TapCaptureResult.HardenedRefusal, hardened)
        assertEquals(0, taps)
    }

    @Test fun `tap failure and screenshot failure are not retried`() {
        var taps = 0
        var screenshots = 0
        val tapFailed = performTapCapture(
            execution(),
            hardened = { false },
            nowElapsedMs = { 100 },
            tap = { _, _ -> taps += 1; null },
            settle = { true },
            screenshot = { screenshots += 1; null },
        )
        assertTrue(tapFailed is TapCaptureResult.TapFailed)
        assertEquals(1, taps)
        assertEquals(0, screenshots)

        val screenshotFailed = performTapCapture(
            execution(),
            hardened = { false },
            nowElapsedMs = { 100 },
            tap = { _, _ -> taps += 1; PrivilegeRoute.ACCESSIBILITY },
            settle = { true },
            screenshot = { screenshots += 1; null },
        )
        assertEquals(TapCaptureResult.ScreenshotFailed(PrivilegeRoute.ACCESSIBILITY), screenshotFailed)
        assertEquals(2, taps)
        assertEquals(1, screenshots)
    }

    @Test fun `completion deadline prevents capture after the tap without reinjecting`() {
        var now = 850L
        var taps = 0
        var screenshots = 0
        val result = performTapCapture(
            execution(completeBefore = 1_000, settleMs = 150),
            hardened = { false },
            nowElapsedMs = { now },
            tap = { _, _ -> taps += 1; PrivilegeRoute.DAEMON },
            settle = { delay -> now += delay; true },
            screenshot = { screenshots += 1; RoutedValue(PrivilegeRoute.DAEMON, byteArrayOf()) },
        )

        assertEquals(TapCaptureResult.CompletionUnknown(PrivilegeRoute.DAEMON), result)
        assertEquals(1, taps)
        assertEquals(0, screenshots)
    }

    @Test fun `interrupted settle reports ambiguous completion and does not capture`() {
        var screenshots = 0
        val result = performTapCapture(
            execution(),
            hardened = { false },
            nowElapsedMs = { 100 },
            tap = { _, _ -> PrivilegeRoute.SU },
            settle = { false },
            screenshot = { screenshots += 1; RoutedValue(PrivilegeRoute.SU, byteArrayOf()) },
        )

        assertEquals(TapCaptureResult.CompletionUnknown(PrivilegeRoute.SU), result)
        assertEquals(0, screenshots)
    }

    @Test fun `capture finishing after the deadline is completion unknown`() {
        var now = 100L
        var screenshots = 0
        val result = performTapCapture(
            execution(completeBefore = 1_000),
            hardened = { false },
            nowElapsedMs = { now },
            tap = { _, _ -> PrivilegeRoute.SU },
            settle = { delay -> now += delay; true },
            screenshot = {
                screenshots += 1
                now = 1_000
                RoutedValue(PrivilegeRoute.SU, byteArrayOf(1))
            },
        )

        assertEquals(TapCaptureResult.CompletionUnknown(PrivilegeRoute.SU), result)
        assertEquals(1, screenshots)
    }
}
