package io.panelassistant.android.control

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.platform.ShellPrivilege
import io.panelassistant.android.shizuku.ShizukuBridge
import io.panelassistant.android.util.HelperClient

/** One coherent display-sizing read; current and base share one routed density observation. */
internal data class DisplaySizingObservation(
    val current: Int?,
    val base: Int?,
    val fontScale: Float,
)

/**
 * Display-sizing control: **density (DPI)** via `wm density` + **text size (font scale)** via
 * `settings system font_scale`. Together these decide whether a Home-Assistant dashboard designed in a
 * desktop browser renders at a matching size: density sets the dp viewport (physical px ÷ density/160 —
 * the *layout* scale), and the system font scale drives WebView text (`textZoom = fontScale × 100` —
 * the *text* scale). Panel firmware often ships a density/font that doesn't match the physical display,
 * so HA cards come out too big/small or text mis-sized vs desktop; iOS keeps these aligned, Android
 * panels frequently don't.
 *
 * Both persist across reboot (secure/system settings), so a one-shot set sticks. Both are privileged.
 * The active profile's `appCanSu` value orders live su/helper attempts; it never suppresses the
 * alternate route.
 */
class DensityController(
    private val canSu: Boolean,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val shell: ShellPrivilege = ShizukuBridge,
) {
    private data class DensityState(val base: Int, val override: Int?)

    /**
     * Read the effective/base density once and the font scale once, then project one immutable result.
     * Android calls the reset target `Physical density`, but it is a logical density, not measured PPI.
     */
    internal fun observeSizing(privilege: PrivilegedRouteObservation? = null): DisplaySizingObservation {
        val density = densityState(privilege)
        return DisplaySizingObservation(
            current = density?.let { it.override ?: it.base },
            base = density?.base,
            fontScale = readFontScale(privilege),
        )
    }

    /** Set the override density (dpi). Bounded to keep the UI usable/bootable. Returns true if applied. */
    fun set(dpi: Int): Boolean {
        if (dpi < MIN_DPI || dpi > MAX_DPI) return false
        return routedEffect(
            su = { root.run("wm density $dpi") },
            helper = { daemon.send("DENSITY $dpi") == "OK" },
            shizuku = { shell.setDensity(dpi) },
        )
    }

    /** Remove the override and restore Android's factory/base logical density. */
    fun reset(): Boolean = routedEffect(
        su = { root.run("wm density reset") },
        helper = { daemon.send("DENSITY reset") == "OK" },
        shizuku = shell::resetDensity,
    )

    private fun readFontScale(privilege: PrivilegedRouteObservation? = null): Float = routedValue(
        privilege = privilege,
        su = { parseRootScale(root.runOutput("settings get system font_scale 2>/dev/null")) },
        helper = { parseHelperScale(daemon.send("FONTSCALE")) },
        shizuku = { parseRootScale(shell.fontScale()) },
    ) ?: 1.0f

    /** Set the system font scale (text size). Bounded to keep text legible. Returns true if applied. */
    fun setFontScale(scale: Float): Boolean {
        if (!scale.isFinite() || scale < MIN_FONT || scale > MAX_FONT) return false
        return routedEffect(
            su = { root.run("settings put system font_scale $scale") },
            helper = { daemon.send("FONTSCALE $scale") == "OK" },
            shizuku = { shell.setFontScale(scale) },
        )
    }

    /** Restore the default font scale (1.0). */
    fun resetFontScale(): Boolean = routedEffect(
        su = { root.run("settings delete system font_scale") },
        helper = { daemon.send("FONTSCALE reset") == "OK" },
        shizuku = shell::resetFontScale,
    )

    private fun densityState(privilege: PrivilegedRouteObservation? = null): DensityState? = routedValue(
        privilege = privilege,
        su = { parseRootDensity(root.runOutput("wm density 2>/dev/null")) },
        helper = { parseHelperDensity(daemon.send("DENSITY")) },
        shizuku = { parseRootDensity(shell.density()) },
    )

    private fun parseRootDensity(reply: String?): DensityState? {
        if (reply == null) return null
        fun field(key: String): Int? = reply.lineSequence()
            .firstOrNull { it.contains(key) }
            ?.substringAfter(key)?.trim()?.toIntOrNull()
        // `wm density` calls the reset reference "Physical density" even though it is Android's base
        // logical density. Keep the parser label, but do not carry that misleading name upward.
        val base = field("Physical density:") ?: return null
        return DensityState(base, field("Override density:"))
    }

    private fun parseHelperDensity(reply: String?): DensityState? {
        val match = DENSITY_REPLY.matchEntire(reply?.trim().orEmpty()) ?: return null
        // PHYS mirrors the `wm density` wire label; semantically it is the base logical density.
        val base = match.groupValues[1].toIntOrNull() ?: return null
        val override = match.groupValues[2].takeUnless { it == "-" }?.toIntOrNull()
        return DensityState(base, override)
    }

    private fun parseRootScale(reply: String?): Float? {
        if (reply == null) return null
        val token = reply.trim()
        if (token.isEmpty()) return null
        return parseScaleToken(token)
    }

    private fun parseHelperScale(reply: String?): Float? {
        val token = SCALE_REPLY.matchEntire(reply?.trim().orEmpty())?.groupValues?.get(1) ?: return null
        return parseScaleToken(token)
    }

    private fun parseScaleToken(token: String): Float? {
        if (token == "null") return 1.0f
        return token.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    }

    private fun routedEffect(
        su: () -> Boolean,
        helper: () -> Boolean,
        shizuku: () -> Boolean,
    ): Boolean {
        val suAttempt = EffectAttempt(PrivilegeRoute.SU, su)
        val helperAttempt = EffectAttempt(PrivilegeRoute.DAEMON, helper)
        val shizukuAttempt = EffectAttempt(PrivilegeRoute.SHIZUKU, shizuku)
        val attempts = when {
            canSu -> arrayOf(suAttempt, helperAttempt, shizukuAttempt)
            shell.available() -> arrayOf(helperAttempt, shizukuAttempt, suAttempt)
            else -> arrayOf(helperAttempt, suAttempt, shizukuAttempt)
        }
        return ShortOperationRouter.effect(*attempts) != null
    }

    private fun <T : Any> routedValue(
        privilege: PrivilegedRouteObservation? = null,
        su: () -> T?,
        helper: () -> T?,
        shizuku: () -> T?,
    ): T? {
        val suAttempt = ValueAttempt(PrivilegeRoute.SU, su)
        val helperAttempt = ValueAttempt(PrivilegeRoute.DAEMON, helper)
        val shizukuAttempt = ValueAttempt(PrivilegeRoute.SHIZUKU, shizuku)
        val ordered = when {
            canSu -> arrayOf(suAttempt, helperAttempt, shizukuAttempt)
            privilege?.shizuku?.ready == true -> arrayOf(helperAttempt, shizukuAttempt, suAttempt)
            privilege == null && shell.available() -> arrayOf(helperAttempt, shizukuAttempt, suAttempt)
            else -> arrayOf(helperAttempt, suAttempt, shizukuAttempt)
        }
        val attempts = if (privilege == null) {
            ordered
        } else {
            ordered.filter { privilege.admits(it.route) }.toTypedArray()
        }
        return ShortOperationRouter.value(*attempts)?.value
    }

    companion object {
        const val MIN_DPI = 80
        const val MAX_DPI = 640
        const val MIN_FONT = 0.5f
        const val MAX_FONT = 1.5f

        private val DENSITY_REPLY = Regex("^PHYS=(\\d+) OVER=(\\d+|-)$")
        private val SCALE_REPLY = Regex("^SCALE=(null|[0-9]+(?:\\.[0-9]+)?)$")

        /** True only when at least one requested display effect exists and every requested effect succeeded. */
        internal fun allApplied(vararg results: Boolean?): Boolean {
            val requested = results.filterNotNull()
            return requested.isNotEmpty() && requested.all { it }
        }
    }
}
