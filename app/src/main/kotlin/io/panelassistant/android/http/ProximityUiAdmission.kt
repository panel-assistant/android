package io.panelassistant.android.http

/**
 * Admits the HTML UI, either on the LAN (marker plus a same-origin browser Origin or Referer) or embedded in the
 * Panel Assistant sidebar, whose proxy forwards neither but asserts `Sec-Fetch-Site: same-origin` and adds
 * `X-Panel-Assistant-Embed`. A cross-site page cannot send either without a CORS preflight the panel never
 * answers. Calibration collection additionally requires Begin on the visible physical panel.
 */
internal fun proximityUiRequestAllowed(
    origin: String?, referer: String?, host: String?, fetchSite: String?, uiMarker: String?, embed: String? = null,
): Boolean {
    val browser = !origin.isNullOrBlank() || !referer.isNullOrBlank()
    val lanUi = uiMarker == "1" && browser && (fetchSite == null || fetchSite == "same-origin")
    val embedded = !browser && fetchSite == "same-origin" && EmbedMode.parse(embed) != null
    return (lanUi || embedded) && OriginGuard.allowed("POST", origin, referer, host)
}
