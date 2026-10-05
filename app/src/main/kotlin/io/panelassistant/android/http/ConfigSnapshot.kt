package io.panelassistant.android.http

import io.panelassistant.android.config.SettingSpec

/** Omit untouched vendor Zigbee ownership rather than recording it as an explicit OFF choice. */
internal fun shouldSnapshotConfigSetting(key: String, zigbeeRouterConfigured: Boolean): Boolean =
    key != "zigbee_router" || zigbeeRouterConfigured

internal fun projectConfigSnapshot(
    specs: Iterable<SettingSpec>,
    zigbeeRouterConfigured: Boolean,
    excludedKeys: Set<String> = emptySet(),
    effectiveValue: (SettingSpec) -> String,
): LinkedHashMap<String, String> {
    val snapshot = LinkedHashMap<String, String>()
    specs.forEach { spec ->
        if (spec.transient || spec.key in excludedKeys) return@forEach
        if (!shouldSnapshotConfigSetting(spec.key, zigbeeRouterConfigured)) return@forEach
        snapshot[spec.key] = effectiveValue(spec)
    }
    return snapshot
}

/** Schema-1 backups made before ownership-aware omission cannot distinguish untouched vendor state
 * from an explicit OFF. On an untouched target, preserve vendor ownership; explicit ON remains safe. */
internal fun preserveUnconfiguredZigbeeOwnership(
    values: MutableMap<String, String>,
    targetConfigured: Boolean,
): Boolean {
    if (targetConfigured || values["zigbee_router"] != "false") return false
    values.remove("zigbee_router")
    return true
}

internal fun fleetImportPreservesTargetLocalValue(fleet: Boolean, key: String, normalized: String): Boolean =
    fleet && key == "ha_url" && normalized.isEmpty()
