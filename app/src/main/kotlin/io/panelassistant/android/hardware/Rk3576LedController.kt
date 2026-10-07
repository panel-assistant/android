package io.panelassistant.android.hardware

import android.util.Log

/**
 * LED adapter for the Electron WF1589T rk3576 panel. Drives `/dev/ledjni` directly
 * through ha-paneld's own NDK ([NativeLed]) — clean-room ioctl, NO vendor `libjnielc.so`, no root,
 * no helper. The char device is world-rwx + app-accessible (SELinux `device` label).
 *
 * HA sends r/g/b as 0..255; the per-profile [transfer] maps each channel to the hardware value that
 * reproduces the requested colour on this panel's non-linear LED before the ioctl. [available] is gated
 * on the native lib loading AND the node being openable, so only the rk3576 panel reports the LED entity.
 */
class Rk3576LedController(
    private val transfer: LedTransfer = LedTransfer.Rk3576FourBit,
    private val driver: LedNativeDriver = AndroidLedNativeDriver,
) : LedController {

    override fun available(): Boolean = driver.available()

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int): Boolean {
        val rc = driver.setRgb(transfer.toHardware(r), transfer.toHardware(g), transfer.toHardware(b))
        if (rc != 0) Log.w(TAG, "setRgb failed rc=$rc") else Log.d(TAG, "rgb -> ($r,$g,$b)")
        return rc == 0
    }

    override fun off(): Boolean {
        val rc = driver.off()
        if (rc != 0) Log.w(TAG, "off failed rc=$rc")
        return rc == 0
    }

    companion object {
        private const val TAG = "ha-paneld/led-rk3576"
    }
}

/** Injectable native boundary so result propagation and transfer mapping are JVM-testable. */
interface LedNativeDriver {
    fun available(): Boolean
    fun setRgb(r: Int, g: Int, b: Int): Int
    fun off(): Int
}

object AndroidLedNativeDriver : LedNativeDriver {
    override fun available() = NativeLed.available()
    override fun setRgb(r: Int, g: Int, b: Int) = NativeLed.nativeSetRgb(r, g, b)
    override fun off() = NativeLed.nativeOff()
}
