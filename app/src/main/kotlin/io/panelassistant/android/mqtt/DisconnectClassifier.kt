package io.panelassistant.android.mqtt

/**
 * Classify an MQTT disconnect cause message into the panel's connection state, so the UI / info page can
 * distinguish "auth rejected" from "unreachable" rather than showing a bare "down". Pure string mapping,
 * lifted out of the HiveMQ disconnected-listener so it's unit-testable. Auth is checked before network so
 * a message that mentions both (e.g. "NOT_AUTHORIZED on this connection") reads as the auth failure.
 * Returns "auth-failed", "unreachable", or "disconnected".
 */
fun classifyDisconnect(causeMessage: String?): String {
    val m = (causeMessage ?: "").uppercase()
    return when {
        Regex("NOT_AUTHORIZED|BAD_USER_NAME|PASSWORD|AUTHENTICAT|BANNED").containsMatchIn(m) -> "auth-failed"
        Regex("REFUSED|TIMEOUT|UNREACHABLE|UNRESOLVED|UNKNOWNHOST|RESET|NOROUTETOHOST|NO ROUTE|CONNECTION|FAILED")
            .containsMatchIn(m) -> "unreachable"
        else -> "disconnected"
    }
}
