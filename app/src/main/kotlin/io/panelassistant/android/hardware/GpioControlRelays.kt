package io.panelassistant.android.hardware

/** Reviewed board mappings for relays on the vendor `/dev/gpio_control` ioctl. */
object GpioControlRelays {
    /** Electron SMT97T: relay 1 is GPIO 4 and relay 2 is GPIO 5 (ha-integration #37). */
    val SMT97_GPIOS: List<Int> = listOf(4, 5)

    // The observed SMT97T: Build.DEVICE rk3566_t and a build string ZX-SMT97-R... The hyphen keeps a
    // ZX-SMT970 or any other ZX-SMT97x build out.
    private const val SMT97_DEVICE = "rk3566_t"
    private const val SMT97_BUILD_PREFIX = "zx-smt97-"

    /** True when [gpios] is the reviewed mapping and the live [device] and [buildDisplay] are the SMT97T's. */
    fun boardMatches(gpios: List<Int>, device: String, buildDisplay: String): Boolean =
        gpios == SMT97_GPIOS &&
            device.lowercase() == SMT97_DEVICE &&
            buildDisplay.lowercase().startsWith(SMT97_BUILD_PREFIX)
}
