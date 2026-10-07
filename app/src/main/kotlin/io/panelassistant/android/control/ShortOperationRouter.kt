package io.panelassistant.android.control

/** The route that completed a short privileged operation. */
internal enum class PrivilegeRoute { DAEMON, SU, ACCESSIBILITY }

/** One ordered effect attempt. Returning false means the next route may be tried. */
internal data class EffectAttempt(
    val route: PrivilegeRoute,
    val execute: () -> Boolean,
)

/** One ordered value attempt. Returning null means the next route may be tried. */
internal data class ValueAttempt<T : Any>(
    val route: PrivilegeRoute,
    val execute: () -> T?,
)

/** A value together with the transport that produced an accepted result. */
internal data class RoutedValue<T : Any>(
    val route: PrivilegeRoute,
    val value: T,
)

/**
 * Ordered routing for short privileged work. The router knows nothing about Android, helper verbs,
 * or shell commands: each caller defines what success means for that operation, and a failed attempt
 * cannot suppress a later safe route merely because its transport was reachable.
 */
internal object ShortOperationRouter {
    fun effect(vararg attempts: EffectAttempt): PrivilegeRoute? =
        attempts.firstOrNull { it.execute() }?.route

    fun <T : Any> value(vararg attempts: ValueAttempt<T>): RoutedValue<T>? {
        for (attempt in attempts) {
            val value = attempt.execute() ?: continue
            return RoutedValue(attempt.route, value)
        }
        return null
    }
}
