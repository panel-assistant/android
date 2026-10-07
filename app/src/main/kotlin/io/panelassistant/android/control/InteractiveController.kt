package io.panelassistant.android.control

import android.view.KeyEvent
import io.panelassistant.android.input.PanelAccessibilityService
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.HelperClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Live routing for interactive operations whose usable transport can differ from profile metadata.
 * The active profile's `appCanSu` value orders attempts only: known su panels try root first, while sandboxed
 * profiles try the helper or accessibility service first. An operation-level failure always falls
 * through to the other safe route.
 *
 * Screenshot deliberately keeps the helper's raw-byte protocol: empty bytes/EOF are failure, not a
 * textual `ERR` reply. A non-empty byte stream is accepted exactly as it was by the HTTP endpoint.
 */
internal class InteractiveController(
    private val canSu: Boolean,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val accessibility: AccessibilityActions = PanelAccessibilityService,
) {
    private val screenshotLock = ReentrantLock(true)

    fun screenshot(): ByteArray? = screenshotWithRoute()?.value

    /**
     * Capture one screenshot and retain the route that supplied it. Ordinary screenshot requests keep
     * the historical fail-fast single-flight behavior. A tap-and-capture request may wait behind the
     * one capture already in progress so an unrelated Dashboard refresh cannot consume its post-tap
     * frame.
     */
    fun screenshotWithRoute(waitForInFlightMs: Long = 0L): RoutedValue<ByteArray>? {
        return withScreenshotLock(waitForInFlightMs) {
            val su = ValueAttempt(PrivilegeRoute.SU) {
                root.runBytesBounded("screencap -p", MAX_SCREENSHOT_BYTES)
                    ?.takeUnless { it.isEmpty() }
            }
            val helper = ValueAttempt(PrivilegeRoute.DAEMON) {
                daemon.sendBytesBounded("SCREENCAP", MAX_SCREENSHOT_BYTES)
                    ?.takeUnless { it.isEmpty() }
            }
            if (canSu) ShortOperationRouter.value(su, helper) else ShortOperationRouter.value(helper, su)
        }
    }

    /** One post-tap capture route. Unlike the general screenshot endpoint, this cannot multiply the
     * combined operation's completion time by falling through several privileged transports. */
    fun screenshotOnceWithRoute(waitForInFlightMs: Long): RoutedValue<ByteArray>? =
        withScreenshotLock(waitForInFlightMs) {
            val attempt = if (canSu) {
                ValueAttempt(PrivilegeRoute.SU) {
                    root.runBytesBounded("screencap -p", MAX_SCREENSHOT_BYTES)
                        ?.takeUnless { it.isEmpty() }
                }
            } else {
                ValueAttempt(PrivilegeRoute.DAEMON) {
                    daemon.sendBytesBounded("SCREENCAP", MAX_SCREENSHOT_BYTES)
                        ?.takeUnless { it.isEmpty() }
                }
            }
            ShortOperationRouter.value(attempt)
        }

    private inline fun <T> withScreenshotLock(waitForInFlightMs: Long, block: () -> T): T? {
        val acquired = try {
            screenshotLock.tryLock(waitForInFlightMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null
        return try {
            block()
        } finally {
            screenshotLock.unlock()
        }
    }

    fun back(): Boolean = navigate(
        rootCommand = "input keyevent ${KeyEvent.KEYCODE_BACK}",
        accessibilityAction = accessibility::back,
    )

    fun recents(): Boolean = navigate(
        rootCommand = "input keyevent ${KeyEvent.KEYCODE_APP_SWITCH}",
        accessibilityAction = accessibility::recents,
    )

    fun tap(x: Float, y: Float): Boolean = tapWithRoute(x, y) != null

    fun tapWithRoute(x: Float, y: Float): PrivilegeRoute? {
        if (!validCoordinates(x, y)) return null
        val xi = x.toInt()
        val yi = y.toInt()
        return routeInput(
            su = { root.run("input tap $xi $yi") },
            accessibility = { accessibility.tap(xi, yi) },
        )
    }

    /** Select one input transport and make one side-effecting attempt. This deliberately gives up
     * fallback availability: a timeout after submission is ambiguous and must never inject again. */
    fun tapOnceWithRoute(x: Float, y: Float): PrivilegeRoute? {
        if (!validCoordinates(x, y)) return null
        val xi = x.toInt()
        val yi = y.toInt()
        val attempt = if (canSu) {
            EffectAttempt(PrivilegeRoute.SU) { root.runSingleAttempt("input tap $xi $yi") }
        } else {
            EffectAttempt(PrivilegeRoute.ACCESSIBILITY) { accessibility.tap(xi, yi) }
        }
        return ShortOperationRouter.effect(attempt)
    }

    private fun validCoordinates(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x <= Int.MAX_VALUE && y <= Int.MAX_VALUE

    private fun navigate(rootCommand: String, accessibilityAction: () -> Boolean): Boolean =
        routeInput(
            su = { root.run(rootCommand) },
            accessibility = accessibilityAction,
        ) != null

    private fun routeInput(
        su: () -> Boolean,
        accessibility: () -> Boolean,
    ): PrivilegeRoute? {
        val suAttempt = EffectAttempt(PrivilegeRoute.SU, su)
        val accessibilityAttempt = EffectAttempt(PrivilegeRoute.ACCESSIBILITY, accessibility)
        return if (canSu) ShortOperationRouter.effect(suAttempt, accessibilityAttempt)
        else ShortOperationRouter.effect(accessibilityAttempt, suAttempt)
    }

    private companion object {
        const val MAX_SCREENSHOT_BYTES = 32L * 1024 * 1024
    }
}
