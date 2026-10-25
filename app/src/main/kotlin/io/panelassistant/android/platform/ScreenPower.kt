package io.panelassistant.android.platform

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.migrateSetupQuestionsForExistingInstall
import io.panelassistant.android.setupEverCompleted

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
     * put a credential screen between the user and the dashboard. What that means depends on the
     * route: [io.panelassistant.android.device.ScreenOff.KEYEVENT] refuses to sleep on a secured
     * device, while the screen-timeout and device-admin routes sleep anyway and report the
     * PIN-on-wake consequence. The bl_power routes are unaffected: they blank the backlight while the
     * device stays Awake, so no keyguard is ever raised.
     */
    fun isDeviceSecure(): Boolean = false

    /** Whether the app holds `WRITE_SETTINGS` (`Settings.System.canWrite`). */
    fun canWriteSystemSettings(): Boolean = false

    /** `Settings.System.SCREEN_OFF_TIMEOUT` in ms, or -1 when it cannot be read. */
    fun screenOffTimeoutMs(): Int = -1

    /** Write `SCREEN_OFF_TIMEOUT`; true only when the write was accepted and reads back. */
    fun setScreenOffTimeoutMs(ms: Int): Boolean = false

    /** Whether the app holds `WRITE_SECURE_SETTINGS` (granted over ADB by Panel Assistant). */
    fun canWriteSecureSettings(): Boolean = false

    /** `Settings.Global.STAY_ON_WHILE_PLUGGED_IN` bit mask, or -1 when it cannot be read. Any set
     *  bit keeps Android awake on that power source whatever the screen timeout says. */
    fun stayOnWhilePluggedInMask(): Int = -1

    /** Write `STAY_ON_WHILE_PLUGGED_IN`; true only when the write was accepted and reads back. */
    fun setStayOnWhilePluggedInMask(mask: Int): Boolean = false

    /** Whether [PanelDeviceAdminReceiver] is an active device admin. */
    fun isDeviceAdminActive(): Boolean = false

    /** `DevicePolicyManager.lockNow()`; false when it could not be issued. */
    fun lockNow(): Boolean = false

    /** Give up [PanelDeviceAdminReceiver] if it is active; false when that could not be asked. */
    fun releaseDeviceAdmin(): Boolean = false
}

/**
 * Device admin with the force-lock policy only, so the screen-off can use `lockNow()` without root.
 * Panel Assistant activates it after setup and after updates (`dpm set-active-admin`). An unfinished
 * first run gives it up so Android Settings can uninstall an abandoned installation.
 */
class PanelDeviceAdminReceiver(
    private val config: (Context) -> Config = ::Config,
    private val power: (Context) -> ScreenPower = ::AndroidScreenPower,
) : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        reconcileSetupDeviceAdmin(config(context), power(context))
    }
}

/** Also run at service startup to release an admin left by an earlier, abandoned installation. */
internal fun reconcileSetupDeviceAdmin(config: Config, power: ScreenPower) {
    // Legacy configured panels need their existing completion migration before this decision.
    config.migrateSetupQuestionsForExistingInstall()
    if (!config.setupEverCompleted) power.releaseDeviceAdmin()
}

/**
 * Gives up [PanelDeviceAdminReceiver] when Panel Assistant is about to uninstall the app: Android
 * refuses to uninstall an active device admin, and the shell cannot remove one, so only the app can.
 * Guarded by DUMP, which the shell holds and an ordinary app cannot.
 */
class ReleaseDeviceAdminReceiver(
    private val power: (Context) -> ScreenPower = ::AndroidScreenPower,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (power(context).releaseDeviceAdmin()) Log.i(TAG, "device admin released for uninstall")
    }

    private companion object {
        const val TAG = "ReleaseDeviceAdmin"
    }
}

/** Real [ScreenPower] over the Android PowerManager. */
class AndroidScreenPower(context: Context) : ScreenPower {
    private val app = context.applicationContext ?: context
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val admin = ComponentName(app, PanelDeviceAdminReceiver::class.java)

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

    override fun canWriteSystemSettings(): Boolean = runCatching { Settings.System.canWrite(app) }.getOrDefault(false)

    override fun screenOffTimeoutMs(): Int = runCatching {
        Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
    }.getOrDefault(-1)

    override fun setScreenOffTimeoutMs(ms: Int): Boolean = runCatching {
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms) &&
            screenOffTimeoutMs() == ms
    }.getOrDefault(false)

    override fun canWriteSecureSettings(): Boolean = runCatching {
        app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun stayOnWhilePluggedInMask(): Int = runCatching {
        Settings.Global.getInt(app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, -1)
    }.getOrDefault(-1)

    override fun setStayOnWhilePluggedInMask(mask: Int): Boolean = runCatching {
        Settings.Global.putInt(app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, mask) &&
            stayOnWhilePluggedInMask() == mask
    }.getOrDefault(false)

    override fun isDeviceAdminActive(): Boolean = runCatching { policy?.isAdminActive(admin) == true }.getOrDefault(false)

    override fun lockNow(): Boolean = runCatching {
        val dpm = policy ?: return false
        if (!dpm.isAdminActive(admin)) return false
        dpm.lockNow()
        true
    }.getOrDefault(false)

    // Removal finishes once Android has told the receiver it is disabled, so it may still be listed
    // for a moment after this returns; Panel Assistant retries the uninstall until it is gone.
    override fun releaseDeviceAdmin(): Boolean = runCatching {
        val dpm = policy ?: return false
        if (!dpm.isAdminActive(admin)) return false
        dpm.removeActiveAdmin(admin)
        true
    }.getOrDefault(false)
}
