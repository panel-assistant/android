package io.panelassistant.android.hardware

import android.util.Log

/**
 * LED adapter for panels whose light is an addressable WS2812 strip behind `/dev/ws2812_pwm` (an
 * Electron WF1589T variant). The whole strip shows one colour. The driver has no brightness control,
 * so Home Assistant's brightness arrives already applied to the RGB values. The strip length is not
 * reported by the driver, so the frame covers [ledCount] LEDs; data for LEDs beyond the end of a shorter
 * strip is shifted out and ignored, as the vendor library does with its own default of 500.
 */
class Ws2812LedController(
    private val driver: Ws2812Driver = NativeWs2812,
    private val ledCount: Int = DEFAULT_LED_COUNT,
) : LedController {

    override fun available(): Boolean = driver.available()

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int): Boolean {
        val rc = driver.fill(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), ledCount)
        if (rc != 0) Log.w(TAG, "fill failed rc=$rc")
        return rc == 0
    }

    override fun off(): Boolean = setRgb(0, 0, 0)

    companion object {
        const val DEFAULT_LED_COUNT = 500
        private const val TAG = "ha-paneld/led-ws2812"
    }
}

/** Injectable native boundary so the controller and backend choice are JVM-testable. */
interface Ws2812Driver {
    fun available(): Boolean
    fun fill(r: Int, g: Int, b: Int, count: Int): Int
}

object NativeWs2812 : Ws2812Driver {
    private val loaded: Boolean = try {
        System.loadLibrary("hapaneld_led")
        true
    } catch (e: Throwable) {
        Log.i("ha-paneld/led-ws2812", "libhapaneld_led not loadable on this ABI; WS2812 strip disabled", e)
        false
    }

    override fun available(): Boolean = loaded && nativeProbe()
    override fun fill(r: Int, g: Int, b: Int, count: Int): Int = if (loaded) nativeFill(r, g, b, count) else -1

    private external fun nativeProbe(): Boolean
    private external fun nativeFill(r: Int, g: Int, b: Int, count: Int): Int
}
