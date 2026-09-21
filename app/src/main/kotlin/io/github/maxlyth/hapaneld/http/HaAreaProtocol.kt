package io.github.maxlyth.hapaneld.http

import org.json.JSONObject

internal fun haAreaCacheEntryUsable(
    cachedFor: String,
    requestedFor: String,
    cachedAtMs: Long,
    nowMs: Long,
    ttlMs: Long,
): Boolean {
    val age = nowMs - cachedAtMs
    return cachedFor == requestedFor && age >= 0L && age < ttlMs
}

/**
 * Pure decisions for the panel's Home Assistant Area — read, match, and the precedence rule.
 *
 * Home Assistant increasingly builds dynamic dashboards from areas, so a wall panel's device should sit
 * in the right one. The registry READS (`config/area_registry/list`, `config/device_registry/list`) are
 * available to any authenticated user, but the WRITES (`config/device_registry/update`, which is how an
 * area is assigned, and `config/area_registry/create`) are admin-only — so everything here is honest
 * about what the signed-in account can actually do.
 *
 * The precedence rule: **Home Assistant's value, when set, is
 * canonical.** The local `ha_area` setting records the panel's *requested* area — it seeds the MQTT
 * discovery `suggested_area` (which applies only at first device registration) and drives write-back
 * attempts — but a differing area reported by HA overwrites the local value, never the reverse. That
 * keeps the non-admin story coherent: a non-admin's choice stands only while HA has none, and an admin's
 * later change in HA flows down instead of fighting.
 *
 * Pure — no Android imports, unit-tested in HaAreaProtocolTest.
 */
object HaAreaProtocol {
    data class HaArea(val areaId: String, val name: String, val icon: String = "")

    /** The panel device's registry state; [found] false means "could not identify our device row". */
    data class PanelDeviceArea(
        val found: Boolean = false,
        val deviceId: String = "",
        val areaId: String = "",
        val areaName: String = "",
    )

    enum class ReconcileAction {
        /** HA reports a different area: adopt it locally — HA is canonical. */
        ADOPT_HA,

        /** HA has no area, we have a request, and the session may write: apply the request. */
        WRITE_BACK,

        /** Nothing to change. */
        KEEP,
    }

    /**
     * The precedence rule as one total function.
     *
     * HA is canonical over ADOPTED values — but a value a PERSON chose is a deliberate local override
     * that adoption must never undo. The first version had no such distinction, so saving a divergent
     * area was impossible: the convergence pass reverted it seconds after the save (hardware report,
     * 2026-07-26 — a panel sits in an HA area with no motion entities, and its area
     * is deliberately set to a neighbouring room so auto-sleep has sources). Blank local = "follow HA".
     * A user override matching HA (any casing) is not overriding anything, so HA's spelling is adopted
     * and the caller should clear the override bit.
     */
    fun reconcile(localName: String, haName: String, admin: Boolean, userOverride: Boolean = false): ReconcileAction {
        val local = localName.trim()
        val ha = haName.trim()
        return when {
            userOverride && local.isNotBlank() && ha.isNotBlank() && !ha.equals(local, ignoreCase = true) ->
                ReconcileAction.KEEP
            ha.isNotBlank() && !ha.equals(local, ignoreCase = true) -> ReconcileAction.ADOPT_HA
            // Same area but HA's casing differs: HA's spelling is the one users see everywhere else.
            ha.isNotBlank() && ha != local -> ReconcileAction.ADOPT_HA
            ha.isBlank() && local.isNotBlank() && admin -> ReconcileAction.WRITE_BACK
            else -> ReconcileAction.KEEP
        }
    }

    /**
     * Whether an unprompted reconciliation is worth attempting.
     *
     * The precedence rule above was true only of readers, and every reader was a UI control: the browser's
     * area picker and the wizard's dashboard step. So a panel nobody had opened that dropdown on never
     * adopted anything — affected panels sat with a blank `ha_area` while their Home Assistant
     * devices had real areas, every surface faithfully reporting "No area" (reported 2026-07-26). The rule
     * now needs an owner that runs without a person, which is what this gates: HA must be reachable and
     * credentialled, since the registry read is an authenticated WebSocket call.
     */
    fun canQueryUnprompted(haUrl: String, credentialed: Boolean): Boolean =
        haUrl.isNotBlank() && credentialed

    /** Reduce `config/area_registry/list` to the fields the pickers need, in HA's own order. */
    fun areas(areaResponse: JSONObject?): List<HaArea> {
        val rows = areaResponse?.optJSONArray("result") ?: return emptyList()
        val areas = mutableListOf<HaArea>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("area_id").trim()
            val name = row.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            areas += HaArea(id, name, row.optString("icon").trim())
        }
        return areas
    }

    /**
     * Find this panel's own device row by the MQTT identifiers it publishes — the minted
     * `ha-paneld-uid-<deviceUid>` preferred, the historical `ha-paneld-<panelId>` as fallback — and join
     * its `area_id` against the area list. Unlike the presence path this never throws: setup must be able
     * to say "couldn't find the device" calmly.
     *
     * `ha-paneld-aid-<androidId>` is deliberately NOT matched, even though existing registrations still
     * carry it: it is duplicated across a cloned fleet (#155), so preferring it is what resolved several
     * panels to one device row in the first place. Every panel publishes its `panel_id` identifier on
     * every pass, so the fallback covers an installation that has not yet been seen under its minted one.
     */
    fun panelDeviceArea(
        deviceResponse: JSONObject?,
        areas: List<HaArea>,
        deviceUid: String,
        panelId: String,
    ): PanelDeviceArea {
        val rows = deviceResponse?.optJSONArray("result") ?: return PanelDeviceArea()
        val devices = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val immutable = deviceUid.trim().takeIf(String::isNotEmpty)?.let { "ha-paneld-uid-$it" }
        val legacy = "ha-paneld-${panelId.trim()}"
        val exact = devices.filter { immutable != null && hasMqttIdentifier(it, immutable) }
        val matches = exact.ifEmpty { devices.filter { hasMqttIdentifier(it, legacy) } }
        val device = matches.singleOrNull() ?: return PanelDeviceArea()
        val areaId = device.optString("area_id").trim()
        val areaName = areas.firstOrNull { it.areaId.equals(areaId, ignoreCase = true) }?.name
            ?: areaId // an id with no registry row is still shown rather than hidden
        return PanelDeviceArea(
            found = true,
            deviceId = device.optString("id").trim(),
            areaId = areaId,
            areaName = if (areaId.isBlank()) "" else areaName,
        )
    }

    /** Case-insensitive area-name resolution, because names are what people type and remember. */
    fun resolveAreaId(areas: List<HaArea>, name: String): String? =
        areas.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.areaId

    private fun hasMqttIdentifier(device: JSONObject, identifier: String): Boolean {
        val identifiers = device.optJSONArray("identifiers") ?: return false
        for (index in 0 until identifiers.length()) {
            val tuple = identifiers.optJSONArray(index) ?: continue
            if (tuple.length() == 2 && tuple.optString(0) == "mqtt" && tuple.optString(1) == identifier) return true
        }
        return false
    }
}
