package io.panelassistant.android.camera

/**
 * What a stream URL asked for — `rtsp://<panel>:8554/live?res=720p&fps=15&kbps=2000`. Unrecognised
 * parameters and unparsable values are ignored, and an omitted one takes the configured value.
 *
 * **The configured values are defaults, not ceilings, and that is a deliberate reversal.** They used to
 * clamp: a URL could ask for less and never for more. Two things made that wrong. It bought nothing
 * against the case it appeared to protect — several viewers share one encode session, because
 * `CameraSessionState` binds only the first stream client, so concurrency costs packetisation and
 * network rather than a second encode, and no ceiling was ever doing that work. And in the deployment
 * this feature is actually for, configured once and left alone, no client passes parameters at all, so
 * the ceiling never clamped anything and only made the settings hard to name.
 *
 * What still bounds a request is the registry's own range for each setting, which is a property of what
 * the encoder and the product support rather than something an operator has to think about.
 */
data class StreamRequest(val resolution: CameraResolution? = null, val fps: Int? = null, val kbps: Int? = null) {

    /**
     * Resolve against the configured defaults. An omitted parameter takes the configured value; a
     * supplied one wins, in either direction, bounded only by the range the product supports.
     */
    fun bind(
        defaultResolution: CameraResolution,
        defaultFps: Int,
        defaultKbps: Int,
    ): Bound = Bound(
        resolution = resolution ?: defaultResolution,
        fps = (fps ?: defaultFps).coerceIn(MIN_FPS, MAX_FPS),
        kbps = (kbps ?: defaultKbps).coerceIn(MIN_KBPS, MAX_KBPS),
    )

    data class Bound(val resolution: CameraResolution, val fps: Int, val kbps: Int) {
        val binding: StreamBinding get() = StreamBinding(fps = fps, kbps = kbps)
    }

    companion object {
        const val MIN_FPS = 1
        const val MAX_FPS = 30
        /** The registry floor for the bitrate setting; below this H.264 at any size is not a picture. */
        const val MIN_KBPS = 250

        /** The registry ceiling for the bitrate setting; the product's bound, not an operator's. */
        const val MAX_KBPS = 8_000

        fun parse(query: Map<String, String>): StreamRequest = StreamRequest(
            resolution = CameraResolution.parse(query["res"]),
            fps = query["fps"]?.toIntOrNull(),
            kbps = query["kbps"]?.toIntOrNull(),
        )

        /** The query part of a URL (after `?`, `#` and beyond dropped); the values are plain tokens, so no percent-decoding. */
        fun parseQuery(url: String): Map<String, String> {
            val q = url.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return emptyMap()
            return q.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=')
                if (key.isEmpty()) null else key to pair.substringAfter('=', "")
            }.toMap()
        }

        fun fromUrl(url: String): StreamRequest = parse(parseQuery(url))
    }
}
