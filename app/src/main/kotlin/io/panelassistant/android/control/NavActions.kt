package io.panelassistant.android.control

/**
 * Back / Recents global navigation. Android forbids an app from injecting these from the sandbox, so
 * there are exactly two routes: root key injection, or an enabled accessibility service.
 *
 * The profile selects the preferred route, not the only route. A failed preferred action falls through
 * to the other live capability, covering stale profile assumptions and a temporarily unbound service.
 *
 * Both calls block on a `su`/binder round-trip — invoke from a background thread (the MQTT command
 * thread is fine; the overlay button offloads to a worker).
 */
object NavActions {

    fun back(appCanSu: Boolean): Boolean = InteractiveController(canSu = appCanSu).back()

    fun recents(appCanSu: Boolean): Boolean = InteractiveController(canSu = appCanSu).recents()

    fun tap(appCanSu: Boolean, x: Float, y: Float): Boolean =
        InteractiveController(canSu = appCanSu).tap(x, y)
}
