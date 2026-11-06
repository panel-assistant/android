package io.panelassistant.android.platform

/** The helper reads only this captured gateway installation; callers never submit a path. */
const val HELPER_ZIGBEE_DIRECTORY = "/vendor/bin/siliconlabs_host"

data class HelperZigbeeState(
    val files: Set<String>,
    val running: Boolean,
    val guardRunning: Boolean,
    val packageVersion: String?,
) {
    val present: Boolean get() = files.isNotEmpty() || running || guardRunning
}

data class HelperZigbeeProcess(val pid: Int, val startJiffies: Long, val jiffies: Long)

data class HelperZigbeeHealth(
    val state: HelperZigbeeState,
    val gateway: HelperZigbeeProcess?,
    val guard: HelperZigbeeProcess?,
    val totalJiffies: Long,
    val recursiveWatchdogAssignment: Boolean,
    /** Bounded diagnostic input; callers retain only the parsed health fields. */
    val netInfo: String?,
)
