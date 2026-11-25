package io.panelassistant.android.hardware

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.LedMechanism

/**
 * Per-panel RGB LED HAL. The uniform interface lets the `light.<panel>_led` entity look
 * identical fleet-wide; the adapter underneath differs by hardware (clean-room NDK ioctl on the
 * rk3576 `/dev/ledjni`, root sysfs elsewhere). r/g/b are HA range 0..255; adapters scale to the
 * panel's native range. [backend] distinguishes confirmed absence from a route that is temporarily
 * unavailable. Only confirmed absence makes the channel unsupported.
 */
interface LedController {
    fun available(): Boolean

    fun backend(): LedBackend

    /** Last settled topology; reporting can read this without opening the device or helper socket. */
    val confirmedAbsent: Boolean get() = false

    /** True if the backend can set arbitrary RGB; false = brightness-only (HA publishes no colour). */
    fun colorCapable(): Boolean

    /** Apply one RGB value and report whether the backend confirmed the complete operation. */
    fun setRgb(r: Int, g: Int, b: Int): Boolean

    /** Turn every channel off and report whether the backend confirmed the operation. */
    fun off(): Boolean

    /** Release any resources owned by this controller once output has stopped. */
    fun close() = Unit
}

enum class LedBackend(val ready: Boolean, val label: String) {
    LEDJNI(true, "Rockchip /dev/ledjni (RGB)"),
    WS2812(true, "WS2812 /dev/ws2812_pwm (RGB)"),
    HELPER_LEDJNI(true, "Rockchip /dev/ledjni helper daemon (RGB)"),
    HELPER_SYSFS(true, "sysfs helper daemon (RGB)"),
    HELPER_LEGACY(true, "helper daemon (RGB)"),
    UNAVAILABLE(false, "unavailable"),
    ABSENT(false, "none"),
}

/** Used on panels with no known LED backend. */
class NoOpLedController : LedController {
    override fun available() = false
    override fun backend() = LedBackend.ABSENT
    override val confirmedAbsent = true
    override fun colorCapable() = false
    override fun setRgb(r: Int, g: Int, b: Int) = false
    override fun off() = false
}

/**
 * Picks the LED backend from the active [DeviceProfile]'s [DeviceProfile.ledMechanism]:
 * - `RK3576_IOCTL` (e.g. WF1589T): NDK ioctl on the app-accessible `/dev/ledjni`, else a `/dev/ws2812_pwm` strip — app-direct. Falls
 *   back to the daemon if the node isn't actually present (graceful for a mis-profiled panel).
 * - `RK3576_IOCTL_DAEMON` (e.g. SMT1019): the `/dev/ledjni` ioctl is the same, but the app is
 *   SELinux-denied it, so the [SocketLedController] asks the root daemon to issue it (the daemon runs
 *   as root and auto-detects ledjni-vs-sysfs). No app-direct attempt — it would only ever EACCES.
 * - `SYSFS_DAEMON` (e.g. TPA10): the [SocketLedController], talking to the root helper daemon
 *   (`helper/hapaneld-helper`) over the abstract UNIX socket (a sandboxed app can't write sysfs nor exec `su`).
 * - `NONE` (e.g. NSPanel Pro): no LED — skip probing entirely.
 * - `AUTODETECT` (unknown panel): probe rk3576 ioctl, then the daemon (the previous behaviour).
 *
 * The socket path keeps its controller through helper outages. A live LEDPROBE can confirm absence
 * or report a usable route when the helper becomes reachable.
 */
object LedFactory {
    fun detect(profile: DeviceProfile): LedController = when (profile.ledMechanism) {
        LedMechanism.NONE -> NoOpLedController()
        LedMechanism.SYSFS_DAEMON -> socket(profile)
        LedMechanism.RK3576_IOCTL_DAEMON -> socket(profile)
        LedMechanism.RK3576_IOCTL, LedMechanism.AUTODETECT ->
            appDirect(profile, Rk3576LedController(profile.ledTransfer), NativeWs2812())
    }

    /**
     * App-direct LED nodes first: the `/dev/ledjni` RGB LED, then a `/dev/ws2812_pwm` addressable strip (a WF1589T
     * variant has the strip and no ledjni), then the helper daemon.
     */
    internal fun appDirect(
        profile: DeviceProfile,
        ledjni: LedController,
        stripDriver: Ws2812Driver,
        helper: LedController = fallback(profile),
    ): LedController {
        if (ledjni.available()) return ledjni
        val strip = Ws2812LedController(
            transfer = fallbackTransfer(profile.ledTransfer),
            driver = stripDriver,
            ledCount = profile.ledCount ?: Ws2812LedController.DEFAULT_LED_COUNT,
        )
        return StripOrHelperLedController(strip, stripDriver, helper, absenceAllowed = profile.ledMechanism == LedMechanism.AUTODETECT)
    }

    /** A declared daemon route gets the profile's transfer, as the app-direct ioctl route does. */
    private fun socket(profile: DeviceProfile) = SocketLedController(transfer = profile.ledTransfer)

    private fun fallback(profile: DeviceProfile) = SocketLedController(transfer = fallbackTransfer(profile.ledTransfer))

    /**
     * The transfer for the daemon fallback of an ioctl route. A declared curve describes the light and is kept;
     * only the four-bit stub is dropped, because it describes the ioctl's safe drive region, not whatever LED
     * the daemon finds.
     */
    internal fun fallbackTransfer(transfer: LedTransfer): LedTransfer =
        if (transfer === LedTransfer.Rk3576FourBit) LedTransfer.Identity else transfer
}

/**
 * The WS2812 strip and the helper are both live routes on a panel without `/dev/ledjni`. A command goes to the
 * strip while it opens and its frames are accepted, else to the helper, so neither route is dropped after a
 * failure. Commands never probe the helper: only [backend], on the capability refresh, runs its LEDPROBE.
 * The LED is reported absent only for an autodetect profile with no strip node and a helper that found no LED;
 * a profile that declares an LED (rk3576-ioctl) is never absent here, because a strip node can appear later.
 */
internal class StripOrHelperLedController(
    private val strip: LedController,
    private val stripDriver: Ws2812Driver,
    private val helper: LedController,
    private val absenceAllowed: Boolean,
) : LedController {
    // False after the strip refused a frame; a later accepted frame, or the next refresh, tries it again.
    @Volatile private var stripFramesAccepted = true

    private fun stripUsable(): Boolean = stripFramesAccepted && strip.available()

    override fun available(): Boolean = stripUsable() || helper.available()

    override fun backend(): LedBackend {
        // A strip that refused its last frame is reported by the route actually in use, then gets one more try
        // on the next command after this refresh.
        val usable = stripUsable()
        stripFramesAccepted = true
        if (usable) return strip.backend()
        val helperBackend = helper.backend()
        return when {
            helperBackend.ready -> helperBackend
            confirmedAbsent -> LedBackend.ABSENT
            else -> LedBackend.UNAVAILABLE
        }
    }

    override val confirmedAbsent: Boolean get() = absenceAllowed && !stripDriver.nodePresent() && helper.confirmedAbsent
    override fun colorCapable(): Boolean = true

    private fun drive(onStrip: () -> Boolean, onHelper: () -> Boolean): Boolean {
        if (stripUsable()) {
            if (onStrip()) return true
            stripFramesAccepted = false
        }
        return onHelper()
    }

    override fun setRgb(r: Int, g: Int, b: Int): Boolean = drive({ strip.setRgb(r, g, b) }, { helper.setRgb(r, g, b) })
    override fun off(): Boolean = drive({ strip.off() }, { helper.off() })
    override fun close() {
        strip.close()
        helper.close()
    }
}
