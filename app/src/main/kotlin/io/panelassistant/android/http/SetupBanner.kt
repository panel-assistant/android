package io.panelassistant.android.http

import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol

/**
 * Pure decision for the info-page setup banner: what the panel still needs configured, derived from the
 * live MQTT status string and whether a broker is configured. Generated panel identities are complete
 * identities, not missing setup.
 *
 * Extracted from the HTML builder so it's unit-testable — the key regression it guards is the
 * "needs the MQTT broker" false-positive: while the bridge is mid-(re)connect after a config change its
 * status is transient/blank, and a configured broker must NOT be reported as missing then.
 */
object SetupBanner {
    /** Saved MQTT settings do not make MQTT part of a panel already migrated to native authority. */
    fun mqttSetupRequired(authority: String): Boolean =
        authority != PanelAssistantTransportProtocol.AUTHORITY_NATIVE

    fun needs(
        mqttStatus: String,
        brokerConfigured: Boolean,
        mqttUserConfigured: Boolean = false,
        mqttSetupRequired: Boolean = true,
    ): List<String> {
        val needs = mutableListOf<String>()
        when {
            // Panel Assistant carries this panel's entities and commands, so MQTT is not something it needs.
            !mqttSetupRequired -> {}
            mqttStatus.contains("connected") || mqttStatus.contains("connecting") ||
                mqttStatus.contains("auth retrying") -> {} // connected / transient — fine
            !brokerConfigured -> needs.add("MQTT configuration") // discovery / broker setup is not proven yet
            mqttStatus.contains("auth rejected") -> needs.add(
                if (mqttUserConfigured) "valid MQTT credentials (the broker rejected them)"
                else "valid MQTT credentials",
            )
            mqttStatus.contains("unreachable") -> needs.add("a reachable MQTT broker")
            mqttStatus.contains("invalid or unsupported") -> needs.add("a valid MQTT broker URL")
            else -> {} // a broker IS configured but the bridge is mid-(re)connect / initialising — transient
        }
        return needs
    }

    /**
     * What the MQTT bridge is doing right now, for a panel that is still being set up or is reconnecting.
     *
     * @param dashboardStepPending whether a dashboard/renderer choice is genuinely still outstanding. Only
     *   then may this promise a next step. Every MQTT (re)connect re-announces discovery — including the one
     *   after an ordinary app upgrade — so on a fully configured panel the old unconditional copy told the
     *   owner "the dashboard setup step appears next" about a step that did not exist and would never come.
     *   Reported from a configured panel immediately after upgrading to versionCode 464. The state
     *   itself is still worth explaining, because MQTT genuinely is not connected yet; it is the promise that
     *   was wrong, so only the promise is dropped.
     */
    fun progress(
        mqttStatus: String,
        brokerConfigured: Boolean,
        dashboardStepPending: Boolean = false,
        liveState: String = "",
        mqttSetupRequired: Boolean = true,
    ): String? {
        // The status string comes from a stale-while-revalidate snapshot; the canonical live state arrives
        // separately precisely so a FINISHED transition clears this banner within one poll cycle. Without
        // it, "publishing Home Assistant discovery" kept narrating a publish that had completed — the
        // bridge flips announcing→connected on the discovery PUBACK, but the snapshot can lag behind.
        // Blank means the caller has no live reading; trust the snapshot.
        if (!mqttSetupRequired || liveState == "connected") return null
        val next = if (dashboardStepPending) " The dashboard setup step appears next." else ""
        return when {
            !brokerConfigured -> null
            mqttStatus.contains("connecting") || mqttStatus.contains("auth retrying") ->
                "MQTT settings saved — verifying the broker connection. This can take a short while after saving.$next"
            mqttStatus.contains("connected, announcing") ->
                "MQTT connected — publishing Home Assistant discovery.$next"
            else -> null
        }
    }
}
