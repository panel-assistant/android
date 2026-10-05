package io.panelassistant.android.hardware

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.LedMechanism

/**
 * Per-panel RGB LED HAL. The uniform interface lets the MQTT `light.<panel>_led` entity look
 * identical fleet-wide; the adapter underneath differs by hardware (clean-room NDK ioctl on the
 * rk3576 `/dev/ledjni`, root sysfs elsewhere). r/g/b are HA range 0..255; adapters scale to the
 * panel's native range. [available] is false when the panel has no usable LED backend (no node /
 * no root), so the bridge can skip publishing the entity.
 */
interface LedController {
    fun available(): Boolean

    /** True if the backend can set arbitrary RGB; false = brightness-only (HA publishes no colour). */
    fun colorCapable(): Boolean

    /** Apply one RGB value and report whether the backend confirmed the complete operation. */
    fun setRgb(r: Int, g: Int, b: Int): Boolean

    /** Turn every channel off and report whether the backend confirmed the operation. */
    fun off(): Boolean
}

/** Used on panels with no known LED backend. */
class NoOpLedController : LedController {
    override fun available() = false
    override fun colorCapable() = false
    override fun setRgb(r: Int, g: Int, b: Int) = false
    override fun off() = false
}

/**
 * Picks the LED backend from the active [DeviceProfile]'s [DeviceProfile.ledMechanism]:
 * - `RK3576_IOCTL` (e.g. WF1589T): NDK ioctl on the app-accessible `/dev/ledjni` — app-direct. Falls
 *   back to the daemon if the node isn't actually present (graceful for a mis-profiled panel).
 * - `RK3576_IOCTL_DAEMON` (e.g. SMT1019): the `/dev/ledjni` ioctl is the same, but the app is
 *   SELinux-denied it, so the [SocketLedController] asks the root daemon to issue it (the daemon runs
 *   as root and auto-detects ledjni-vs-sysfs). No app-direct attempt — it would only ever EACCES.
 * - `SYSFS_DAEMON` (e.g. TPA10): the [SocketLedController], talking to the root helper daemon
 *   (`helper/hapaneld-helper`) over the abstract UNIX socket (a sandboxed app can't write sysfs nor exec `su`).
 * - `NONE` (e.g. NSPanel Pro): no LED — skip probing entirely.
 * - `AUTODETECT` (unknown panel): probe rk3576 ioctl, then the daemon (the previous behaviour).
 *
 * For the socket path, `detect()` returns the controller without probing; the bridge later calls
 * `available()` (off the main thread), which is false when the daemon has no reachable LED node → the
 * LED entity is skipped. So a panel with neither a node nor the daemon yields no LED entity.
 */
object LedFactory {
    fun detect(profile: DeviceProfile): LedController = when (profile.ledMechanism) {
        LedMechanism.NONE -> NoOpLedController()
        LedMechanism.SYSFS_DAEMON -> socket(profile)
        LedMechanism.RK3576_IOCTL_DAEMON -> socket(profile)
        LedMechanism.RK3576_IOCTL -> Rk3576LedController(profile.ledTransfer).takeIf { it.available() } ?: fallback(profile)
        LedMechanism.AUTODETECT -> Rk3576LedController(profile.ledTransfer).takeIf { it.available() } ?: fallback(profile)
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
