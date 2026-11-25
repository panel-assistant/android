package io.panelassistant.android.hardware

/** Reviewed board mappings for relays on the vendor `/dev/gpio_control` ioctl. */
object GpioControlRelays {
    /** Electron SMT97T: relay 1 is GPIO 4 and relay 2 is GPIO 5 (ha-integration #37). */
    val SMT97_GPIOS: List<Int> = listOf(4, 5)
    private const val SMT97_BUILD_PREFIX = "zx-smt97"

    /** True when [gpios] is a reviewed mapping and [buildDisplay] is that board's live build string. */
    fun boardMatches(gpios: List<Int>, buildDisplay: String): Boolean =
        gpios == SMT97_GPIOS && buildDisplay.lowercase().startsWith(SMT97_BUILD_PREFIX)
}
