package io.panelassistant.android.hardware

import android.util.Log
import android.system.OsConstants

/**
 * LED adapter for panels whose light is an addressable WS2812 strip behind `/dev/ws2812_pwm` (an
 * Electron WF1589T variant). The whole strip shows one colour. The driver has no brightness control,
 * so Home Assistant's brightness arrives already applied to the RGB values. The strip length is not
 * reported by the driver, so the frame covers [ledCount] LEDs; data for LEDs beyond the end of a shorter
 * strip is shifted out and ignored, as the vendor library does with its own default of 500.
 */
class Ws2812LedController(
    private val transfer: LedTransfer = LedTransfer.Identity,
    private val driver: Ws2812Driver = NativeWs2812(),
    private val ledCount: Int = DEFAULT_LED_COUNT,
) : LedController {

    override fun available(): Boolean = driver.available()
    override fun backend(): LedBackend = if (available()) LedBackend.WS2812 else LedBackend.UNAVAILABLE

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int): Boolean {
        val rc = driver.fill(channel(r), channel(g), channel(b), ledCount)
        if (rc != 0) Log.w(TAG, "fill failed rc=$rc")
        return rc == 0
    }

    override fun off(): Boolean = setRgb(0, 0, 0)
    override fun close() = driver.close()

    private fun channel(v: Int) = transfer.toHardware(v).coerceIn(0, 255)

    companion object {
        const val DEFAULT_LED_COUNT = 500
        const val MAX_LED_COUNT = 1024
        private const val TAG = "ha-paneld/led-ws2812"
    }
}

/** Injectable native boundary so the controller and backend choice are JVM-testable. */
interface Ws2812Driver {
    /** True only once the node has opened and accepted the strip timing (SET_PARAM). */
    fun available(): Boolean
    fun fill(r: Int, g: Int, b: Int, count: Int): Int
    fun close() = Unit
}

internal class NativeWs2812 : Ws2812Driver {
    private var fd = -1
    private var closed = false

    @Synchronized
    override fun available(): Boolean {
        if (!loaded || closed) return false
        if (fd < 0) fd = nativeOpen()
        return fd >= 0
    }

    @Synchronized
    override fun fill(r: Int, g: Int, b: Int, count: Int): Int {
        if (!available()) return fd
        val result = nativeFill(fd, r, g, b, count)
        if (result != 0 && result != -OsConstants.EBUSY) release()
        return result
    }

    @Synchronized
    override fun close() {
        closed = true
        release()
    }

    private fun release() {
        if (fd >= 0) nativeClose(fd)
        fd = -1
    }

    private external fun nativeOpen(): Int
    private external fun nativeFill(fd: Int, r: Int, g: Int, b: Int, count: Int): Int
    private external fun nativeClose(fd: Int)

    companion object {
        private val loaded: Boolean by lazy {
            try {
                System.loadLibrary("hapaneld_led")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.i("ha-paneld/led-ws2812", "libhapaneld_led not loadable on this ABI; WS2812 strip disabled", e)
                false
            }
        }
    }
}
