package io.panelassistant.android.control

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log

/**
 * Display sizing by the app itself, for a panel with neither root nor the helper. Text size is a
 * public system setting the `WRITE_SETTINGS` grant covers. Density goes through `cmd window density`
 * run as the app, which Android allows with the `WRITE_SECURE_SETTINGS` grant Panel Assistant makes
 * at install; the hidden `IWindowManager` call it wraps is refused to an app by reflection. Where
 * SELinux enforces (the Meta Portal), the command cannot even find the window service from an app,
 * so density counts as ready only once the command has answered. Every write is read back from the
 * secure setting Android persists it in, so a firmware that refuses the command reports failure.
 */
interface AppSizing {
    /** Whether the density grant is held. */
    fun densityReady(): Boolean

    /** Whether the text-size grant is held. */
    fun fontReady(): Boolean

    /** Base density and the forced override (null when none), or null when unreadable. */
    fun density(): Pair<Int, Int?>?

    /** Force [dpi], or remove the override when null; true only once the setting reads it back. */
    fun setDensity(dpi: Int?): Boolean

    fun fontScale(): Float?

    /** Write [scale]; true only once the setting reads it back. */
    fun setFontScale(scale: Float): Boolean
}

internal class FrameworkSizing(private val context: Context) : AppSizing {
    private val directCommands = DirectAppCommands()
    // The policy that hides the window service from an app cannot change while the process runs.
    private val windowCommandAnswers by lazy { windowDensity(null)?.contains("Physical density") == true }

    override fun densityReady(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED && windowCommandAnswers

    override fun fontReady(): Boolean = Settings.System.canWrite(context)

    override fun density(): Pair<Int, Int?>? {
        // Android's reset reference: ro.sf.lcd_density, which `wm density` labels "Physical density".
        val base = DisplayMetrics.DENSITY_DEVICE_STABLE.takeIf { it > 0 } ?: return null
        return base to forcedDensity()
    }

    private fun forcedDensity(): Int? =
        Settings.Secure.getString(context.contentResolver, DENSITY_FORCED)?.trim()?.toIntOrNull()

    override fun setDensity(dpi: Int?): Boolean {
        if (!densityReady()) return false
        val output = windowDensity(dpi?.toString() ?: "reset") ?: return false
        if (output.isNotEmpty()) Log.i(TAG, "cmd window density: $output")
        return forcedDensity() == dpi
    }

    /** `cmd window density [arg]` as the app: its output, or null when it did not finish in time. */
    private fun windowDensity(arg: String?): String? = directCommands.windowDensity(arg)

    override fun fontScale(): Float? =
        runCatching { Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1.0f) }
            .getOrNull()

    override fun setFontScale(scale: Float): Boolean {
        if (!fontReady()) return false
        return runCatching {
            Settings.System.putFloat(context.contentResolver, Settings.System.FONT_SCALE, scale) &&
                fontScale() == scale
        }.onFailure { Log.w(TAG, "font_scale write refused", it) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "ha-paneld/sizing"
        const val DENSITY_FORCED = "display_density_forced"
    }
}
