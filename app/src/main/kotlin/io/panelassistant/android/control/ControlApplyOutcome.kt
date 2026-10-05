package io.panelassistant.android.control

/**
 * What a hardware controller learned by trying to apply a value.
 *
 * The third case is the point: a controller that returns only a boolean cannot tell its caller that this
 * panel has no path to the hardware at all, so a value it can never apply is retried and reported as
 * "about to apply" on every boot forever. Only the operation that ran may report [UNAVAILABLE], and only
 * when every path it owns reported structural absence — a permission this app does not hold, an
 * executable that is not on the device. Anything that could succeed on the next attempt is [FAILED].
 */
internal enum class ControlApplyOutcome {
    APPLIED,
    FAILED,
    UNAVAILABLE;

    val applied: Boolean get() = this == APPLIED
}
