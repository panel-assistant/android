package io.panelassistant.android.http

/**
 * Distinguishes an omitted probe port from an explicitly malformed one. Falling back for malformed
 * input probes a different destination than the operator entered and can report false confidence.
 */
internal fun selectLogSinkProbePort(explicit: String?, saved: Int): Int? {
    val candidate = if (explicit == null) saved else explicit.toIntOrNull() ?: return null
    return candidate.takeIf { it in 1..65535 }
}
