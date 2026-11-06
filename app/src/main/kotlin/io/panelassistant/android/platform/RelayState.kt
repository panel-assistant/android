package io.panelassistant.android.platform

/** Fixed helper selectors; configured paths are never sent over the socket. */
enum class HelperRelayClass(val sysfsBase: String) {
    RELAY("/sys/class/relay"),
    ST_RELAY("/sys/class/st_relay"),
    STRELAY("/sys/class/strelay");

    companion object {
        fun fromSysfsBase(path: String): HelperRelayClass? = entries.firstOrNull { it.sysfsBase == path }
    }
}

/** Value readability and existing writable-output preparation are independent observations. */
data class HelperGpioState(val value: Boolean?, val prepared: Boolean?)
