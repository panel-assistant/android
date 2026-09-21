package io.github.maxlyth.hapaneld.camera

/**
 * The integer keys an H.264 encode session is configured with, as plain data so the bitrate contract
 * can be asserted without a device. [MediaCodecH264Encoder.open] turns this map into the `MediaFormat`
 * it configures, and adds nothing of its own beyond the colour format and the frame size.
 *
 * The quantiser ceiling is the reason this is worth its own type. Measured on a WF1589T panel
 * (`c2.rk.avc.encoder`): asked for 1000 kbps at 1080p15 the component delivered
 * 4716 kbps while holding its slices at a median QP of 22 and never passing 28 — it had compression
 * headroom in hand and declined to use it, so the configured bitrate was not a bound on anything. The
 * same component asked for 8000 kbps delivered 8094 and dropped to QP 15, so it does follow the target
 * upward. Granting it the whole H.264 quantiser range is what makes the target reachable downward; it
 * is one configure-time key, and nothing measures, adapts or re-targets while a stream runs.
 */
object EncoderFormat {

    /** Seconds between IDRs: a joining client waits at most this long for a decodable picture, and PLAY asks for one sooner. */
    const val IDR_INTERVAL_S = 2

    /** The top of the H.264 quantiser range. A lower ceiling is a quality floor, and a quality floor is a bitrate floor. */
    const val QP_CEILING = 51

    /** `MediaFormat.KEY_BIT_RATE`. */
    const val BIT_RATE = "bitrate"

    /** `MediaFormat.KEY_FRAME_RATE`. */
    const val FRAME_RATE = "frame-rate"

    /** `MediaFormat.KEY_I_FRAME_INTERVAL`. */
    const val I_FRAME_INTERVAL = "i-frame-interval"

    /** `MediaFormat.KEY_BITRATE_MODE`; the value is `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR`. */
    const val BITRATE_MODE = "bitrate-mode"

    const val BITRATE_MODE_CBR = 2

    /** `MediaFormat.KEY_VIDEO_QP_MAX`, and its per-picture-type companions, all from API 31. */
    const val QP_MAX = "video-qp-max"
    const val QP_I_MAX = "video-qp-i-max"
    const val QP_P_MAX = "video-qp-p-max"
    const val QP_B_MAX = "video-qp-b-max"

    /** The first API level carrying the quantiser keys. */
    const val QP_KEYS_FROM_SDK = 31

    /**
     * [bps] is the bitrate the session was bound to, already reduced to what the chosen encoder
     * declares it can do. [cbr] is set only when that encoder offers constant bitrate. The quantiser
     * ceiling is stated for every picture type, because a component may read the general key, the
     * per-type keys, or only some of them, and a ceiling left unstated is the vendor's own.
     */
    fun keys(fps: Int, bps: Int, cbr: Boolean, sdkInt: Int): Map<String, Int> = buildMap {
        put(BIT_RATE, bps)
        put(FRAME_RATE, fps)
        put(I_FRAME_INTERVAL, IDR_INTERVAL_S)
        if (cbr) put(BITRATE_MODE, BITRATE_MODE_CBR)
        if (sdkInt >= QP_KEYS_FROM_SDK) {
            put(QP_MAX, QP_CEILING)
            put(QP_I_MAX, QP_CEILING)
            put(QP_P_MAX, QP_CEILING)
            put(QP_B_MAX, QP_CEILING)
        }
    }
}
