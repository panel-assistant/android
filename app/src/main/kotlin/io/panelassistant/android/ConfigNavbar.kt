package io.panelassistant.android

import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.util.SystemProps

// Navigation-bar mode resolution: the stored mode, the profile and firmware evidence for a native bar.

/**
 * The navbar mode this panel should actually run, given what it has stored.
 *
 * The fresh-install default itself is [navbarModeDefault], the sole definition of that rule, which
 * lives in the settings registry beside the spec it belongs to and is reached here through
 * [SettingSpec.defaultFor]. This function answers the separate question of what to do with a value
 * that is already on disk, and there are exactly two answers.
 *
 * **A stored value the user chose is kept, including `Off`.** That is the whole reason a derived
 * default cannot change an existing panel's navigation on upgrade: it is consulted only where nothing
 * was ever written for the key.
 *
 * **A stored value can become invalid after the fact**, because config bundles carry `navbar_mode` and
 * are imported with no capability filter at all, so a bundle captured on a panel with a native bar can
 * land on one without. Coercing such a value to the derived default rather than to `Off` is
 * deliberate — on a panel with no system bar that yields working navigation instead of none.
 */
internal fun resolveNavbarMode(
    stored: String?,
    caps: Capabilities,
    default: String = requireNotNull(SettingsRegistry.spec("navbar_mode")).defaultFor(caps),
): String {
    if (stored == null) return default
    if (!navbarModePermitted(stored, caps.hasNativeNavbar)) return default
    return stored
}

/**
 * Profile declarations win over Android's own signals; an undeclared navbar consults firmware
 * visibility and Android. One exception: a profile declaring no native bar yields to the Sonoff vendor
 * property `persist.smatek.show.navigationbar` reading explicitly true, because Sonoff SystemUI then
 * draws Android's bar and a drawn overlay would stack a second bar on it. A missing or malformed
 * property keeps the declaration, so a panel with nothing readable never defaults to Native.
 */
internal fun resolveNativeNavbar(
    declared: Boolean?,
    vendorProperty: String?,
    androidShowsNavbar: Boolean?,
    mainKeys: String? = null,
): Boolean? {
    val vendor = vendorProperty?.trim()?.lowercase(java.util.Locale.ROOT)
    if (declared == false && vendor in VENDOR_TRUE) return true
    if (declared != null) return declared
    return when (vendor) {
        "false", "0", "no", "off" -> false
        in VENDOR_TRUE -> true
        null, "" -> when (mainKeys?.trim()) {
            "1" -> false
            "0" -> true
            null, "" -> androidShowsNavbar
            else -> null
        }
        else -> null
    }
}

private val VENDOR_TRUE = setOf("true", "1", "yes", "on")

/** Whether [mode] may be selected on a panel with this capability. Shared by the read-side coercion
 *  and by the HTTP and command write admission, so they cannot drift apart. */
internal fun navbarModePermitted(mode: String, hasNativeNavbar: Boolean): Boolean =
    hasNativeNavbar || !mode.equals("Native", ignoreCase = true)

// Navigation bar mode: "Off" | "Always on" | "Swipe reveal" | "Native" (NavbarController.MODES).
// The first three govern ha-paneld's own drawn overlay; "Native" means the firmware's own bar has
// authority and is only selectable where a declaration or firmware probe confirms one. Persisted so the mode is
// restored on boot; a stored value that this panel may no longer use falls back to the default.
val Config.navbarMode: String
    get() {
        val caps = navbarCapabilities()
        return resolveNavbarMode(prefs.getString("navbar_mode", null), caps, effectiveDefault(specOf("navbar_mode"), caps))
    }

/**
 * The capability inputs [navbarModeDefault] reads, built here from the resolved profile plus the
 * firmware probes only this Android edge can read. The service builds a much larger
 * snapshot for discovery and option gating; the profile-declared fields are drawn from the same
 * profile in both places, so the two cannot disagree about what this panel declares.
 *
 * `hardwareDeclarationsKnown` is what holds the no-way-out tier closed until the declarations
 * behind it have actually been read from a catalog profile — false for the JVM-test seams, where
 * no profile is resolved, and for the last-resort profile used when the catalog fails to load.
 */
internal fun Config.navbarCapabilities(): Capabilities = Capabilities(
    hasRecents = profile?.hasRecents == true,
    hasEvdevButtons = profile?.evdevButtons?.isNotEmpty() == true,
    hasNativeNavbar = hasNativeNavbar,
    androidShowsNavbar = nativeNavbarPresence,
    hardwareDeclarationsKnown = profile?.declarationsFromCatalog == true,
)

/** Usable native navigation: an explicit profile declaration, otherwise the firmware probe ([resolveNativeNavbar]). */
internal val Config.hasNativeNavbar: Boolean get() = nativeNavbarPresence == true

private val Config.nativeNavbarPresence: Boolean? get() {
    val declared = profile?.hasNativeNavbar
    if (declared == true) return true
    return resolveNativeNavbar(
        declared,
        SystemProps.get("persist.smatek.show.navigationbar"),
        resourceShowsNavbar(),
        SystemProps.get("qemu.hw.mainkeys"),
    )
}

/** A zero-height standard status bar cannot be made visible by app-level immersive mode. */
internal val Config.hasAndroidStatusBar: Boolean get() {
    val res = resources ?: return false
    val id = res.getIdentifier("status_bar_height", "dimen", "android")
    return id != 0 && runCatching { res.getDimensionPixelSize(id) > 0 }.getOrDefault(false)
}

private fun Config.resourceShowsNavbar(): Boolean? {
    val res = resources
    val id = res?.getIdentifier("config_showNavigationBar", "bool", "android") ?: 0
    return id.takeIf { it != 0 }?.let { runCatching { res?.getBoolean(it) }.getOrNull() }
}
