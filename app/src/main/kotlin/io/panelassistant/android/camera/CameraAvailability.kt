package io.panelassistant.android.camera

/**
 * Why this panel does or does not offer a camera.
 *
 * The capability itself is one boolean and always has been: `hardware.camera` if the profile states it,
 * otherwise whatever Android enumerates. That boolean is enough to decide whether to offer the settings
 * and enough to refuse a frame, but it is not enough to tell anybody what to do, and the three ways of
 * arriving at "no" need different answers. A profile that suppresses a camera is a decision somebody
 * made; a board that enumerates none has no camera to offer; a probe that has not answered yet knows
 * nothing and must not be reported as either. The fourth case is the mirror image: a profile that
 * declares a camera the board does not expose is fully capable as far as every gate is concerned, and
 * fails at every open with `no_camera_id`.
 *
 * This is the single rule. [cameraCapabilityPresent] is derived from it rather than stated twice, so a
 * reason and a capability cannot disagree.
 */
enum class CameraCapabilityReason(val wire: String) {
    /** The panel has a camera: declared, enumerated, or both. */
    PRESENT("present"),

    /** The active profile sets `hardware.camera: false`, which suppresses even an enumerated camera. */
    SUPPRESSED_BY_PROFILE("suppressed_by_profile"),

    /** The profile says nothing and Android enumerated no camera. This board has none. */
    NOT_ENUMERATED("not_enumerated"),

    /** The profile says nothing and the enumeration has not answered yet. Not a statement about hardware. */
    UNDETERMINED("undetermined"),

    /** The profile declares a camera the board does not expose; the capability holds and every open fails. */
    MISDECLARED("misdeclared"),
    ;

    /** Whether the panel offers the camera at all. The settings gate and every refusal read this. */
    val capable: Boolean get() = this == PRESENT || this == MISDECLARED
}

/**
 * Classifies the capability from the two inputs that decide it: what the active profile declares
 * (`true` forces the camera on, `false` suppresses it, absent defers) and what the enumeration probe
 * observed (`null` while it has not answered).
 *
 * Pure and Android-free. `PaneldService` is the one place that reads `profile.cameraDeclared`; it passes
 * both values here rather than a second reader growing anywhere else.
 */
fun cameraCapabilityReason(declared: Boolean?, observed: Boolean?): CameraCapabilityReason = when {
    declared == false -> CameraCapabilityReason.SUPPRESSED_BY_PROFILE
    declared == true -> if (observed == false) {
        CameraCapabilityReason.MISDECLARED
    } else {
        CameraCapabilityReason.PRESENT
    }
    observed == true -> CameraCapabilityReason.PRESENT
    observed == false -> CameraCapabilityReason.NOT_ENUMERATED
    else -> CameraCapabilityReason.UNDETERMINED
}
