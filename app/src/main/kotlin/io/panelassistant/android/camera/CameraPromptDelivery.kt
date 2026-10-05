package io.panelassistant.android.camera

/**
 * When the activity may actually raise the camera permission dialog. Android will only show it from a
 * resumed activity, and AndroidX reports `RESUMED` only after `onResume` returns — so a signal that
 * arrives during `onResume` itself, or while paused, must be held and delivered at the next observably
 * resumed point rather than dropped by a lifecycle guard that never retries.
 *
 * Pure so the ordering is a unit test: a signal before resume is pending until [onResumed]; a signal
 * while resumed asks at once; a pause holds any later signal; nothing is asked twice for one signal.
 */
class CameraPromptDelivery(private val ask: () -> Unit) {
    private var resumed = false
    private var pending = false

    /** Call from a point where the activity is observably resumed (the lifecycle `ON_RESUME` event). */
    @Synchronized
    fun onResumed() {
        resumed = true
        if (pending) {
            pending = false
            ask()
        }
    }

    @Synchronized
    fun onPaused() {
        resumed = false
    }

    /** The prompt state flipped to "ask". Delivered now if resumed, otherwise held for the next resume. */
    @Synchronized
    fun onSignal() {
        if (resumed) ask() else pending = true
    }

    val isResumed: Boolean @Synchronized get() = resumed
    val hasPending: Boolean @Synchronized get() = pending
}
