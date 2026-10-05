package io.panelassistant.android.http

import io.panelassistant.android.Config

/** A setting's effective current value: controller-sourced live state where it exists, identity
 *  fields resolved (panel_id auto-derives when unset), else the persisted value. */
internal fun effectiveSettingValue(config: Config, spec: io.panelassistant.android.config.SettingSpec, live: Map<String, String>): String =
    live[spec.key] ?: when (spec.key) {
        "panel_id" -> config.panelId
        "friendly_name" -> config.friendlyName
        else -> config.getRaw(spec)
    }
