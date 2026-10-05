package io.panelassistant.android.hardware

import android.util.Log

/**
 * Clean-room NDK binding for the rk3576 panel front RGB LED, driven directly via the app-accessible
 * char device `/dev/ledjni`. The native side (`app/src/main/cpp/led_jni.c`) is ha-paneld's OWN code
 * — NO vendor `libjnielc.so` is bundled or loaded. The node is world-rwx + app-accessible (SELinux
 * `device` label), so this works WITHOUT root or a helper daemon.
 *
 * ioctl protocol (reverse-engineered clean-room): `open(O_RDONLY|O_NOCTTY)`; `ioctl(fd,0xa1,R)`;
 * `ioctl(fd,0xa2,G)`; `ioctl(fd,0xa3,B)`; `close`. Per-channel value 0..15. off = `ioctl(fd,0x99,0)`.
 * Callers pass values already scaled to 0..15.
 *
 * The library is bundled in the APK, so [loaded] is normally true; it only fails if the device ABI
 * wasn't built. [available] additionally probes that `/dev/ledjni` can be opened, so non-rk3576
 * panels (no such node) report unavailable and the LED entity is skipped.
 */
object NativeLed {
    private val loaded: Boolean = try {
        System.loadLibrary("hapaneld_led")
        true
    } catch (e: Throwable) {
        Log.i(TAG, "libhapaneld_led not loadable on this ABI — LED native disabled", e)
        false
    }

    /** True only on a panel where the native lib loaded AND /dev/ledjni is openable (i.e. rk3576). */
    fun available(): Boolean = loaded && nativeProbe()

    external fun nativeProbe(): Boolean
    external fun nativeSetRgb(r: Int, g: Int, b: Int): Int
    external fun nativeOff(): Int

    private const val TAG = "ha-paneld/led-ndk"
}
