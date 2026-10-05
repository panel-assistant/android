package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The URL is the fine-grained configuration surface, and the settings are its defaults.
 *
 * They used to be ceilings a URL could not exceed. That was reversed deliberately: several viewers share
 * one encode session, so a ceiling never did the concurrency work it appeared to, and in the deployment
 * this feature is for nobody passes parameters at all, so it clamped nothing while making the settings
 * impossible to name. What still bounds a request is the range the product supports.
 */
class StreamRequestTest {

    @Test fun omittedParametersTakeTheConfiguredDefaults() {
        val bound = StreamRequest().bind(CameraResolution.P720, defaultFps = 15, defaultKbps = 2_000)
        assertEquals(StreamRequest.Bound(CameraResolution.P720, fps = 15, kbps = 2_000), bound)
    }

    @Test fun aUrlWinsInEitherDirectionBecauseTheSettingsAreDefaults() {
        val less = StreamRequest.fromUrl("rtsp://panel:8554/live?res=480p&fps=5&kbps=500")
            .bind(CameraResolution.P720, defaultFps = 15, defaultKbps = 2_000)
        assertEquals(StreamRequest.Bound(CameraResolution.P480, fps = 5, kbps = 500), less)

        // The case that used to be clamped back to the configured values, and is the whole reversal.
        val more = StreamRequest.fromUrl("rtsp://panel:8554/live?res=1080p&fps=30&kbps=8000")
            .bind(CameraResolution.P720, defaultFps = 15, defaultKbps = 2_000)
        assertEquals(
            "a URL asking for more than the configured default now gets it",
            StreamRequest.Bound(CameraResolution.P1080, fps = 30, kbps = 8_000),
            more,
        )
    }

    @Test fun theProductRangeStillBoundsARequestTheOperatorDidNotSet() {
        // Not an operator ceiling — the range the encoder and the product support. A URL cannot leave it.
        val absurd = StreamRequest(fps = 9_000, kbps = 9_000_000)
            .bind(CameraResolution.P720, defaultFps = 15, defaultKbps = 2_000)
        assertEquals(StreamRequest.MAX_FPS, absurd.fps)
        assertEquals(StreamRequest.MAX_KBPS, absurd.kbps)
    }

    @Test fun unrecognisedParametersAndUnparsableValuesAreIgnored() {
        val request = StreamRequest.fromUrl("rtsp://panel/live?codec=h265&res=4k&fps=fast&kbps=&audio=1#frag")
        assertEquals(StreamRequest(), request)
        assertNull(request.resolution)
        assertNull(request.fps)
        assertNull(request.kbps)
    }

    @Test fun theFloorsHoldEvenWhenTheUrlAsksForNothingUsable() {
        val bound = StreamRequest(fps = 0, kbps = 1).bind(CameraResolution.P480, defaultFps = 15, defaultKbps = 2_000)
        assertEquals(StreamRequest.MIN_FPS, bound.fps)
        assertEquals(StreamRequest.MIN_KBPS, bound.kbps)
        assertEquals(StreamBinding(fps = 1, kbps = 250), bound.binding)
    }

    @Test fun theQueryParserKeepsOnlyKeyValuePairs() {
        assertEquals(mapOf("res" to "720p", "fps" to "15"), StreamRequest.parseQuery("rtsp://p:8554/live?res=720p&fps=15"))
        assertEquals(emptyMap<String, String>(), StreamRequest.parseQuery("rtsp://p:8554/live"))
        assertEquals(mapOf("a" to ""), StreamRequest.parseQuery("/live?a&=b"))
    }
}
