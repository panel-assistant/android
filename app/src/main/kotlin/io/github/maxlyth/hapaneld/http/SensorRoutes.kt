package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.sensorValuesRoute(
    appContext: Context,
    sensors: SensorReporter,
    volumePercent: () -> Int,
    effectiveBrightness: () -> Int,
) {
    // Live Sensors card: last-published values + live extras. Volume is the current
    // media-stream percent; brightness is the system setting (0-255, -1 unknown).
    get("/sensors") {
        // Effective backlight first (reflects firmware dims); raw setting as fallback.
        val bright = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        call.respondText(
            """{${sensors.valuesJson()},"volume_pct":${runCatching { volumePercent() }.getOrDefault(-1)},"brightness":$bright}""",
            ContentType.Application.Json,
        )
    }
}
