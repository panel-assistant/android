package io.panelassistant.android.platform

/** Current connection fields only; Android remains the authority for the active network. */
data class HelperWifiStatus(val ssid: String?, val rssiDbm: Int?)
