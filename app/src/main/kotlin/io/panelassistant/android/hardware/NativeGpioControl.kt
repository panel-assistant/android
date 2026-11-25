package io.panelassistant.android.hardware

import android.util.Log

/** Relay switching through the vendor `/dev/gpio_control` ioctl; see `app/src/main/cpp/gpio_control_jni.c`. */
interface GpioControl {
    /** True only where the node exists and this app can read and write it. */
    fun available(): Boolean
    /** Drive [gpio] on or off; true when the ioctl succeeded. */
    fun write(gpio: Int, on: Boolean): Boolean
    /** State of [gpio], or null when it cannot be read. */
    fun read(gpio: Int): Boolean?
}

object NativeGpioControl : GpioControl {
    private val loaded: Boolean = try {
        System.loadLibrary("hapaneld_led")
        true
    } catch (e: Throwable) {
        Log.i(TAG, "libhapaneld_led not loadable on this ABI; gpio_control relays disabled", e)
        false
    }

    override fun available(): Boolean = loaded && nativeProbe()
    override fun write(gpio: Int, on: Boolean): Boolean = loaded && nativeWrite(gpio, on) >= 0
    override fun read(gpio: Int): Boolean? = if (!loaded) null else when (nativeRead(gpio)) {
        0 -> false
        1 -> true
        else -> null
    }

    private external fun nativeProbe(): Boolean
    private external fun nativeWrite(gpio: Int, on: Boolean): Int
    private external fun nativeRead(gpio: Int): Int

    private const val TAG = "ha-paneld/gpio-control"
}
