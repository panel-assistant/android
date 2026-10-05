package io.panelassistant.android.platform

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * The platform power and lock facts [io.panelassistant.android.control.ScreenController] needs — a
 * seam so screen on/off is unit-testable without Android. [AndroidScreenPower] is the real impl;
 * tests use a fake.
 */
interface ScreenPower {
    /** True when the device is interactive (screen on) — `PowerManager.isInteractive`. */
    fun isInteractive(): Boolean

    /** Briefly acquire a SCREEN_BRIGHT wakelock that also wakes the device — the wake "pulse". */
    fun pulseWake()

    /** Briefly lift Android idle dim without waking a sleeping device or changing its brightness. */
    fun brightenWhileOn(): Boolean = false

    /**
     * True when a PIN/pattern/password is configured, so leaving Android's interactive state would
     * put a credential screen between the user and the dashboard. A wall panel has nobody to type
     * that credential, which is why [io.panelassistant.android.device.ScreenOff.KEYEVENT] refuses
     * to sleep on a secured device. The bl_power routes are unaffected: they blank the backlight
     * while the device stays Awake, so no keyguard is ever raised.
     */
    fun isDeviceSecure(): Boolean = false
}

/** Real [ScreenPower] over the Android PowerManager. */
class AndroidScreenPower(context: Context) : ScreenPower {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    override fun isInteractive(): Boolean = pm.isInteractive

    @Suppress("DEPRECATION")
    override fun pulseWake() {
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ha-paneld:wake",
        ).acquire(3_000)
    }

    @Suppress("DEPRECATION")
    override fun brightenWhileOn(): Boolean {
        if (!pm.isInteractive) return false
        return runCatching {
            // No ACQUIRE_CAUSES_WAKEUP: a concurrent platform sleep must win over an approach.
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ha-paneld:presence")
                .acquire(5_000)
            true
        }.getOrDefault(false)
    }

    // An unavailable KeyguardManager is treated as secured: refusing to sleep costs a dimmer panel,
    // while guessing "no credential" wrong strands one behind a lock screen nobody can clear.
    override fun isDeviceSecure(): Boolean = keyguard?.isDeviceSecure ?: true
}
