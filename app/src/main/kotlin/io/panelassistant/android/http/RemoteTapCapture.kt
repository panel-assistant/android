package io.panelassistant.android.http

import io.panelassistant.android.control.PrivilegeRoute
import io.panelassistant.android.control.RoutedValue

internal data class TapCaptureExecution(
    val x: Float,
    val y: Float,
    val loopback: Boolean,
    val executeBeforeElapsedMs: Long,
    val completeBeforeElapsedMs: Long,
    val settleMs: Long,
    val maxScreenshotWaitMs: Long,
)

/** One combined operation: at most one tap, followed by at most one screenshot. */
internal fun performTapCapture(
    execution: TapCaptureExecution,
    hardened: () -> Boolean,
    nowElapsedMs: () -> Long,
    tap: (Float, Float) -> PrivilegeRoute?,
    settle: (Long) -> Boolean,
    screenshot: (Long) -> RoutedValue<ByteArray>?,
): TapCaptureResult {
    if (nowElapsedMs() >= execution.executeBeforeElapsedMs) return TapCaptureResult.Expired
    if (hardened() && !execution.loopback) return TapCaptureResult.HardenedRefusal

    val inputRoute = tap(execution.x, execution.y) ?: return TapCaptureResult.TapFailed()
    var remainingMs = execution.completeBeforeElapsedMs - nowElapsedMs()
    if (remainingMs <= execution.settleMs) return TapCaptureResult.CompletionUnknown(inputRoute)
    if (!settle(execution.settleMs)) return TapCaptureResult.CompletionUnknown(inputRoute)

    remainingMs = execution.completeBeforeElapsedMs - nowElapsedMs()
    if (remainingMs <= 0L) return TapCaptureResult.CompletionUnknown(inputRoute)
    val captured = screenshot(remainingMs.coerceAtMost(execution.maxScreenshotWaitMs))
        ?: return TapCaptureResult.ScreenshotFailed(inputRoute)
    if (nowElapsedMs() >= execution.completeBeforeElapsedMs) {
        return TapCaptureResult.CompletionUnknown(inputRoute)
    }
    return TapCaptureResult.Success(inputRoute, captured.route, captured.value)
}
