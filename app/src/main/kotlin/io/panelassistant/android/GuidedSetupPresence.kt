package io.panelassistant.android

/**
 * Whether a person is ACTIVELY walking guided setup right now — evidenced by an explicit heartbeat from
 * the wizard UI, which runs every 2–10 s while the page is open and stops when it closes or completes.
 *
 * Exists because the service's deliberate self-restarts (staged-profile activation, bounded runtime
 * recovery) only checked `!InstallProgress.running` and landed mid-wizard on every fresh-panel walk:
 * the restart takes the local HTTP server down for a few seconds, and the user's save fails with a
 * network error at the exact moment they pressed the button. A walked wizard is not a safe process
 * boundary. An UNATTENDED first-run panel (no browser open) keeps restarting on schedule — the window
 * is presence, not journey state, so staged profiles still activate promptly when nobody is mid-walk.
 */
object GuidedSetupPresence {
    private const val WALK_WINDOW_MS = 90_000L

    @Volatile private var lastHeartbeatElapsedMs = Long.MIN_VALUE

    fun noteHeartbeat(nowElapsedMs: Long) {
        lastHeartbeatElapsedMs = nowElapsedMs
    }

    fun activelyWalked(nowElapsedMs: Long): Boolean =
        lastHeartbeatElapsedMs != Long.MIN_VALUE &&
            nowElapsedMs - lastHeartbeatElapsedMs <= WALK_WINDOW_MS
}
