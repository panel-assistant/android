package io.panelassistant.android.camera

/**
 * Whether the visible activity should ask Android for the camera permission right now. Owned by the
 * session owner, which is the only party that knows all three gates: the profile declares a camera, the
 * master switch is on, and the permission is not yet held. The activity only reads it, so a camera-less
 * panel with an imported `camera_enabled=true` is never prompted.
 *
 * A denial is remembered for as long as the switch stays on — durably, through [store], so a declined
 * panel is not re-asked on the next boot — and a fresh enable is the one event that asks again.
 * "In flight" is process-local and deliberately short: the activity only marks it while it is resumed
 * and actually raising the dialog, and a paused activity never asks, so it cannot strand the state.
 */
object CameraPermissionPrompt {
    /** Durable denial memory; installed once by the session owner. */
    interface Store {
        var declined: Boolean
    }

    private object InMemory : Store {
        @Volatile override var declined: Boolean = false
    }

    @Volatile private var store: Store = InMemory
    @Volatile private var wanted = false
    @Volatile private var inFlight = false
    /** The resumed activity's ear: invoked when [shouldAsk] turns true, so an enable that lands while
     *  it is already on screen prompts at once instead of waiting for an unrelated resume. */
    @Volatile private var listener: (() -> Unit)? = null

    /** Register while resumed, clear on pause. Registering while an ask is already due fires at once. */
    fun setListener(l: (() -> Unit)?) {
        listener = l
        if (l != null && shouldAsk()) l()
    }

    fun install(store: Store) {
        this.store = store
    }

    /** The owner publishes the gate state; a fresh enable also forgets an earlier denial. */
    fun publish(wantsPermission: Boolean, freshEnable: Boolean) {
        val before = shouldAsk()
        if (freshEnable) store.declined = false
        wanted = wantsPermission
        if (!before && shouldAsk()) listener?.invoke()
    }

    fun shouldAsk(): Boolean = wanted && !store.declined && !inFlight

    /** Call only from a resumed activity, immediately before raising the dialog. */
    fun asking() {
        inFlight = true
    }

    fun answered(granted: Boolean) {
        inFlight = false
        store.declined = !granted
        if (granted) wanted = false
    }

    /** The activity is leaving the screen; a dialog that never got an answer must not strand the state. */
    fun activityPaused() {
        inFlight = false
    }

    /** Test seam. */
    internal fun reset() {
        store = InMemory
        InMemory.declined = false
        wanted = false
        inFlight = false
        listener = null
    }
}
