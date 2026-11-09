package io.panelassistant.android.audio

import android.media.MediaRecorder

/** How two captured channels become the canonical mono stream. */
enum class CaptureDownmix(val wireValue: String) {
    /**
     * Per 10 ms block, with hysteresis: a channel at least four times louder than the other is taken
     * alone, otherwise the two are averaged (see [ChannelMixer]). Protects against a dead or near-silent
     * channel, which a plain average halves the signal for.
     */
    DOMINANCE("dominance"),
    AVERAGE("average"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun ofWire(value: String?): CaptureDownmix? = entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

/** Which input device the recorder is asked to prefer once it is built. */
enum class PreferredMicDevice(val wireValue: String) {
    /** No preference: the platform's own routing, which is today's behaviour. */
    AUTO("auto"),
    BUILTIN("builtin"),
    USB("usb");

    companion object {
        fun ofWire(value: String?): PreferredMicDevice? = entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

/** Whether the platform acoustic echo canceller is attached to the capture session. */
enum class PlatformAecMode(val wireValue: String) {
    /** Attached when the platform reports one available. */
    AUTO("auto"),
    ON("on"),
    OFF("off");

    companion object {
        fun ofWire(value: String?): PlatformAecMode? = entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() }
    }
}

/**
 * What the platform echo canceller is doing for the open capture, as the `ha-paneld/mic` log line and the
 * route report spell it.
 */
enum class AecState(val wireValue: String) {
    /** Created on the session, enabled, and the platform reads it back as enabled. */
    ARMED("armed"),

    /** Not asked for: the route turns it off, or an app-side canceller owns echo cancellation. */
    REQUESTED_OFF("requested-off"),

    /** Asked for, but the platform has none for this session. */
    UNAVAILABLE("unavailable"),

    /** Created and enabled, but the platform reads it back as disabled, so it was released. */
    READBACK_FALSE("readback-false"),
}

/**
 * The capture route one microphone source opens: Android audio source, the rate and channel count the
 * recorder is asked for, how two channels become one, the preferred input device, how many reads are
 * discarded after the recorder opens, and the platform echo canceller.
 *
 * Whatever the route, the source delivers the canonical 16 kHz mono stream; [ConvertingCaptureDevice]
 * does the downmix and resampling before the fan-out.
 */
data class CaptureRoute(
    val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    val sampleRateHz: Int = MicrophoneSource.SAMPLE_RATE_HZ,
    val channels: Int = MicrophoneSource.CHANNELS,
    val downmix: CaptureDownmix = CaptureDownmix.DOMINANCE,
    val preferredDevice: PreferredMicDevice = PreferredMicDevice.AUTO,
    val warmupReads: Int = 0,
    val platformAec: PlatformAecMode = PlatformAecMode.OFF,
) {
    /**
     * True for the route the panel opened before routes existed: the configured source at 16 kHz mono with
     * nothing attached, discarded or re-routed. That route is handed to the fan-out without a converting
     * stage, so a panel with no profile block and every setting on automatic captures exactly as before.
     */
    val passthrough: Boolean
        get() = sampleRateHz == MicrophoneSource.SAMPLE_RATE_HZ && channels == 1 && warmupReads == 0 &&
            preferredDevice == PreferredMicDevice.AUTO && platformAec == PlatformAecMode.OFF

    companion object {
        /** Rates the converting stage resamples from. */
        val SUPPORTED_RATES_HZ: Set<Int> = setOf(16_000, 44_100, 48_000)
        val SUPPORTED_CHANNELS: Set<Int> = setOf(1, 2)

        /** Reads discarded after an open when a profile block or setting chooses the route. */
        const val DEFAULT_WARMUP_READS = 16
        const val MAX_WARMUP_READS = 200

        /** The value every capture-route setting takes when it follows the profile. */
        const val AUTO = "auto"

        /** Android audio source constant for a `voice_audio_source` or profile `source` wire value. */
        fun audioSourceFor(setting: String?): Int = when (setting?.trim()?.lowercase()) {
            "mic" -> MediaRecorder.AudioSource.MIC
            "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        /** The wire name for an Android audio source constant, for logs and reports. */
        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.MIC -> "mic"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "voice_communication"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice_recognition"
            else -> source.toString()
        }

        /**
         * Resolve the route. Each of rate, channels and downmix is the owner's setting when it is not
         * `auto`, else the profile's declaration, else the built-in default. [sourceSetting] already
         * carries that order: the profile's source is the setting's default (see `Config.effectiveDefault`).
         *
         * The warm-up discard defaults to [DEFAULT_WARMUP_READS] once a profile block or a setting chooses
         * a route, and to none otherwise; the platform echo canceller defaults to the block's `auto`, and
         * to off without one. Both keep a panel with no block and automatic settings on today's route.
         */
        fun resolve(
            sourceSetting: String?,
            rateSetting: String?,
            channelsSetting: String?,
            downmixSetting: String?,
            declared: MicrophoneCaptureDeclaration?,
        ): CaptureRoute {
            val rate = rateSetting.explicit()?.toIntOrNull()?.takeIf { it in SUPPORTED_RATES_HZ }
                ?: declared?.sampleRateHz?.takeIf { it in SUPPORTED_RATES_HZ }
            val channels = channelsSetting.explicit()?.toIntOrNull()?.takeIf { it in SUPPORTED_CHANNELS }
                ?: declared?.channels?.takeIf { it in SUPPORTED_CHANNELS }
            val downmix = CaptureDownmix.ofWire(downmixSetting.explicit())
                ?: CaptureDownmix.ofWire(declared?.downmix)
            val chosen = declared != null || rate != null || channels != null || downmix != null
            return CaptureRoute(
                source = audioSourceFor(sourceSetting),
                sampleRateHz = rate ?: MicrophoneSource.SAMPLE_RATE_HZ,
                channels = channels ?: MicrophoneSource.CHANNELS,
                downmix = downmix ?: CaptureDownmix.DOMINANCE,
                preferredDevice = PreferredMicDevice.ofWire(declared?.preferredDevice) ?: PreferredMicDevice.AUTO,
                warmupReads = (declared?.warmupReads ?: if (chosen) DEFAULT_WARMUP_READS else 0)
                    .coerceIn(0, MAX_WARMUP_READS),
                platformAec = if (declared == null) PlatformAecMode.OFF
                else PlatformAecMode.ofWire(declared.platformAec) ?: PlatformAecMode.AUTO,
            )
        }

        private fun String?.explicit(): String? = this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != AUTO }
    }
}

/**
 * A profile's `hardware.microphone_capture` block as the audio code reads it. Every field is optional; an
 * absent field takes the built-in default. Wire values are validated by the profile validator.
 */
data class MicrophoneCaptureDeclaration(
    val source: String? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val downmix: String? = null,
    val preferredDevice: String? = null,
    val warmupReads: Int? = null,
    val platformAec: String? = null,
)

/** What the open recorder actually delivers, after the open ladder. */
data class ActiveCapture(
    val source: Int,
    val sampleRateHz: Int,
    val channels: Int,
    /** Null when the capture is one channel and nothing is mixed. */
    val downmix: CaptureDownmix?,
    /** The input device Android routed the recorder to, as `type:name`, or null when unknown. */
    val routedDevice: String?,
    /** Read from the platform for this report; never a remembered value. */
    val aec: AecState,
    /** Reads discarded after this open. */
    val warmupReads: Int,
)

/**
 * The capture route as status surfaces report it: what was requested, what is open now (null while the
 * microphone is closed), and how many times the capture has been rebuilt after a failure.
 */
data class CaptureRouteReport(
    val requested: CaptureRoute,
    val active: ActiveCapture?,
    val rebuilds: Int,
)

/** A microphone source that can say which route it captures through. */
interface CaptureRouteReporting {
    /** A fresh report; the echo canceller state is read from the platform on every call. */
    fun captureRoute(): CaptureRouteReport?
}
