package io.github.maxlyth.hapaneld.camera

/** One H.264 encoder as `MediaCodecList` describes it, reduced to the facts the choice depends on. */
data class EncoderCandidate(
    val name: String,
    val hardware: Boolean,
    /** The declared bitrate range in bits per second. */
    val minBps: Int,
    val maxBps: Int,
    val sizeSupported: Boolean,
)

sealed interface EncoderChoice {
    data class Chosen(val name: String, val bps: Int) : EncoderChoice
    /** A classified reason, safe for `fault_detail`; never an exception message. */
    data class Refused(val detail: String) : EncoderChoice
}

/**
 * Which encoder to open, and at what bitrate, for a session bound to [kbps]. The rules are the
 * resource ceiling in miniature: only a hardware encoder is acceptable, because a software
 * H.264 encoder at 720p on a panel already spending 70–130 % of a core on the dashboard is exactly
 * the cost this feature exists to avoid; and the bitrate ceiling is a ceiling — an encoder whose
 * floor is above it is refused rather than driven past the cap.
 */
object EncoderSelection {
    fun choose(candidates: List<EncoderCandidate>, kbps: Int): EncoderChoice {
        val hardware = candidates.filter { it.hardware }
        if (hardware.isEmpty()) return EncoderChoice.Refused("no_hardware_encoder")
        val fitting = hardware.filter { it.sizeSupported }
        if (fitting.isEmpty()) return EncoderChoice.Refused("size_unsupported")
        val target = kbps * 1_000
        val withinFloor = fitting.filter { it.minBps <= target }
        if (withinFloor.isEmpty()) return EncoderChoice.Refused("bitrate_below_encoder_floor")
        // MediaCodecList order is the platform's preference; the first fitting hardware encoder wins.
        val chosen = withinFloor.first()
        return EncoderChoice.Chosen(chosen.name, bps = minOf(target, chosen.maxBps))
    }

    /** `isHardwareAccelerated` exists from API 29; before that the name is the only evidence there is. */
    fun hardwareByName(name: String): Boolean =
        !name.startsWith("OMX.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.", ignoreCase = true)
}
