package io.panelassistant.android.camera

/**
 * The integer keys an H.264 encode session is configured with, as plain data so the bitrate contract
 * can be asserted without a device. [MediaCodecH264Encoder.open] turns this map into the `MediaFormat`
 * it configures, and adds nothing of its own beyond the colour format and the frame size.
 *
 * Constant bitrate is the reason this is worth its own type, and it is requested of every encoder
 * rather than only of one that declares it. Measured on a WF1589T (`c2.rk.avc.encoder`, which answers
 * `isBitrateModeSupported(CBR)` with false): in the variable-bitrate mode that answer left it in, a
 * 1000 kbps target at 1080p15 delivered 4716 kbps while the slices sat at a median QP of 22 and never
 * passed 28 — compression in hand and declined, so the configured bitrate bounded nothing. The same
 * component, sent the same target with `bitrate-mode` set to CBR, delivered 1899 kbps. The capability
 * query is therefore not evidence of what the component will do, and only `configure` is: ask for
 * constant bitrate, and [MediaCodecH264Encoder.open] falls back to the vendor's own mode if the
 * component refuses the format. Nothing measures, adapts or re-targets while a stream runs.
 */
object EncoderFormat {

    /** Seconds between IDRs: a joining client waits at most this long for a decodable picture, and PLAY asks for one sooner. */
    const val IDR_INTERVAL_S = 2

    /** `MediaFormat.KEY_BIT_RATE`. */
    const val BIT_RATE = "bitrate"

    /** `MediaFormat.KEY_FRAME_RATE`. */
    const val FRAME_RATE = "frame-rate"

    /** `MediaFormat.KEY_I_FRAME_INTERVAL`. */
    const val I_FRAME_INTERVAL = "i-frame-interval"

    /** `MediaFormat.KEY_BITRATE_MODE`. */
    const val BITRATE_MODE = "bitrate-mode"

    /** `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR`. */
    const val BITRATE_MODE_CBR = 2

    /**
     * [bps] is the bitrate the session was bound to, already reduced to what the chosen encoder
     * declares it can do. [constantBitrate] false is the fallback the opener uses after a component
     * has refused the format, and leaves the mode to the vendor.
     */
    fun keys(fps: Int, bps: Int, constantBitrate: Boolean = true): Map<String, Int> = buildMap {
        put(BIT_RATE, bps)
        put(FRAME_RATE, fps)
        put(I_FRAME_INTERVAL, IDR_INTERVAL_S)
        if (constantBitrate) put(BITRATE_MODE, BITRATE_MODE_CBR)
    }
}
