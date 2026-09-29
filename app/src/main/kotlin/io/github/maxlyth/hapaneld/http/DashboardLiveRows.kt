package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

// Live control states (what HA's control entities currently show) — controls, not config.
internal fun liveRowsHtml(
    ledRaw: String,
    brightness: Int?,
    volumePercent: Int,
    lastNavigate: String,
    strings: AppStrings,
): String {
    val led = ledRaw.split(",").mapNotNull { it.toIntOrNull() }
    val ledShown = if (led.size == 5 && led[0] == 1) "${strings.get("dashboard.value.on")} · rgb(${led[2]},${led[3]},${led[4]}) @ ${led[1]}" else strings.get("dashboard.value.off")
    val brightnessShown = brightness?.coerceIn(0, 255)?.let { value ->
        "${(value * 100 + 127) / 255}% ($value)"
    } ?: "?"
    return listOf(
        strings.get("dashboard.live.screen_brightness") to brightnessShown,
        strings.get("dashboard.live.volume") to "${volumePercent}%",
        strings.get("dashboard.live.navigate") to lastNavigate.ifEmpty { "/" },
        strings.get("dashboard.live.led") to ledShown,
    ).joinToString("\n") { (k, v) -> """<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>""" }
}
