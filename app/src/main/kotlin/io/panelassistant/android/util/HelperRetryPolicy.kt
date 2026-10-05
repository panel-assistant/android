package io.panelassistant.android.util

/** Bounded reconnect pacing shared by optional helper-backed streams. Zero means no prior failure. */
internal fun nextHelperRetryDelayMs(previousMs: Long): Long = when {
    previousMs <= 0L -> HELPER_RETRY_INITIAL_MS
    previousMs >= HELPER_RETRY_MAX_MS -> HELPER_RETRY_MAX_MS
    previousMs > HELPER_RETRY_MAX_MS / 2L -> HELPER_RETRY_MAX_MS
    else -> (previousMs * 2L).coerceAtMost(HELPER_RETRY_MAX_MS)
}

internal const val HELPER_RETRY_INITIAL_MS = 3_000L
internal const val HELPER_RETRY_MAX_MS = 60_000L
