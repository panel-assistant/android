package io.panelassistant.android

import io.panelassistant.android.Config.Companion.CONFIG_LOCK
import io.panelassistant.android.config.Capabilities

// Presence: where the panel takes presence from, and the one-time resolution of the legacy panel-sensor
// auto-sleep source, which needs the proximity runtime's wizard model and so cannot run in the schema step.

/**
 * The proximity capabilities this process last measured. Config has no sensor of its own, so the presence
 * default reads them here; null until the service has taken its first capability snapshot.
 */
internal object PresenceCapabilityHint {
    @Volatile var capabilities: Capabilities? = null
}

/** The stored presence mode when it is one of the declared options, otherwise this panel's default. */
val Config.presenceMode: PresenceMode
    get() = PresenceMode.fromWire(
        prefs.getString(PresenceMode.KEY, null)?.takeIf { it in specOf(PresenceMode.KEY).options },
    ) ?: PresenceMode.fromWire(effectiveDefault(specOf(PresenceMode.KEY)))
        ?: PresenceMode.AUTOMATIC

/** Private marker left by the schema-18 step for a panel whose auto-sleep used its own proximity sensor. */
internal const val LEGACY_PANEL_PRESENCE_PENDING_PREF = "presence_mode_legacy_panel_pending"

/**
 * Settle the presence mode of a panel whose auto-sleep used its own sensor before schema 18. Writes the mode
 * and removes the marker in one commit, only while the marker exists, so a second call is a no-op. Returns
 * whether it wrote.
 */
fun Config.resolveLegacyPanelPresence(hasWizardModel: Boolean, ranged: Boolean): Boolean =
    synchronized(CONFIG_LOCK) {
        if (!prefs.contains(LEGACY_PANEL_PRESENCE_PENDING_PREF)) return@synchronized false
        val mode = when {
            hasWizardModel && ranged -> PresenceMode.HYBRID
            !hasWizardModel && ranged -> PresenceMode.AUTOMATIC
            else -> PresenceMode.MANUAL
        }
        durableCommit {
            putString(PresenceMode.KEY, mode.wire)
            remove(LEGACY_PANEL_PRESENCE_PENDING_PREF)
        }
    }
