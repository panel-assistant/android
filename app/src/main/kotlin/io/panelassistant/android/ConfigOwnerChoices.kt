package io.panelassistant.android

import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.hardware.LedEffects
import io.panelassistant.android.http.PaneldServer
import io.panelassistant.android.persistence.ConfigVault

/** Only explicit choices omitted from the registry projection; never installation or security identity. */
internal data class OwnerChoicesPlan(
    val values: Map<String, Any>,
    val skipped: List<String> = emptyList(),
)

/** All selected fields are validated before the caller starts the configuration restore. */
internal fun Config.planOwnerChoices(
    rows: List<ConfigVault.StateRow>,
    samePanel: Boolean,
    profileMatches: Boolean = false,
): OwnerChoicesPlan {
    val selected = rows.filter { it.namespace == "config" && isOwnerChoice(it.key) }
    require(selected.map { it.key }.distinct().size == selected.size) { "duplicate owner choice" }
    val decoded = selected.associate { row ->
        val value: Any = when {
            row.key == "allow_apk_upload" -> {
                require(row.type == "boolean" && row.valueText in setOf("0", "1")) { "invalid APK upload choice" }
                row.valueText == "1"
            }
            row.key.startsWith(BUTTON_LIGHT_PREFIX) -> {
                require(row.type == "int") { "invalid button light type" }
                requireNotNull(row.valueText?.toIntOrNull()).also { require(it in -1..255) { "invalid button light" } }
            }
            else -> {
                require(row.type == "string" && row.valueText != null) { "invalid owner choice type" }
                val text = requireNotNull(row.valueText)
                require(text.toByteArray(Charsets.UTF_8).size <= PaneldServer.MAX_CONFIG_POST_BODY_BYTES) { "owner choice too large" }
                when (row.key) {
                    "auto_sleep_source_exclusions_v1" -> normalizeAutoSleepExclusions(text)
                    PRESENCE_HA_SOURCES_PREF -> normalizePresenceHaSources(text)
                    "update_ignored" -> normalizeIgnoredUpdates(text)
                    "last_led" -> text.also(::validateStoredLed)
                    "last_led_effect" -> text.also {
                        require(it.isEmpty() || LedEffects.Effect.entries.any { effect -> effect.effectName == it }) { "invalid LED effect" }
                    }
                    else -> text
                }
            }
        }
        row.key to value
    }.toMutableMap()
    // Older archives can have a base color without an effect. Reset the effect with it, rather than
    // attaching the receiving install's effect. An effect alone cannot describe an actuator request.
    if ("last_led_effect" in decoded && "last_led" !in decoded) {
        require(decoded["last_led_effect"] == "") { "LED effect has no color" }
    }
    if ("last_led" in decoded) {
        decoded.putIfAbsent("last_led_effect", "")
        if ((decoded["last_led"] as String).let { it.isEmpty() || it.startsWith("0,") }) {
            require(decoded["last_led_effect"] == "") { "LED effect while off" }
        }
    }
    val currentButton = profile?.takeIf { it.hasButtonBacklight }?.let {
        buttonBacklightKey(it)
    }
    val skipped = decoded.keys.filter { key ->
        when {
            key == "allow_apk_upload" -> decoded[key] == true
            key == "last_led" || key == "last_led_effect" ->
                !samePanel || !profileMatches || profile?.ledMechanism.let { it == null || it == LedMechanism.NONE }
            key.startsWith(BUTTON_LIGHT_PREFIX) -> !samePanel || !profileMatches || key != currentButton
            else -> false
        }
    }
    return OwnerChoicesPlan(decoded - skipped.toSet(), skipped)
}

/** A single ordinary Config commit; a failed durable write never counts as restored. */
internal fun Config.applyOwnerChoices(plan: OwnerChoicesPlan): Int = synchronizedTransaction {
    if (plan.values.isEmpty()) return@synchronizedTransaction 0
    val committed = durableCommit {
        plan.values.forEach { (key, value) ->
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                else -> error("unsupported owner choice")
            }
        }
    }
    if (!committed) return@synchronizedTransaction 0
    plan.values.count { (key, value) -> prefs.all[key] == value }
}

private const val BUTTON_LIGHT_PREFIX = "last_button_backlight."

private fun isOwnerChoice(key: String): Boolean = key in setOf(
    "auto_sleep_source_exclusions_v1", "update_ignored", "last_led", "last_led_effect",
    "ui_dashboard_layout", "allow_apk_upload", PRESENCE_HA_SOURCES_PREF,
) || key.startsWith(BUTTON_LIGHT_PREFIX)

private fun validateStoredLed(text: String) {
    if (text.isEmpty()) return
    val parts = text.split(',')
    require(parts.size == 5) { "invalid LED color" }
    val values = parts.map { requireNotNull(it.toIntOrNull()) { "invalid LED color" } }
    require(values[0] in 0..1 && values.drop(1).all { it in 0..255 }) { "invalid LED color" }
}

private fun normalizeIgnoredUpdates(text: String): String {
    if (text.isEmpty()) return text
    val rows = text.split('\n').map { line ->
        val parts = line.split('\t')
        require(parts.size == 2 && parts.all { it.isNotBlank() && '\r' !in it }) { "invalid update dismissal" }
        parts[0] to parts[1]
    }
    require(rows.map { it.first }.distinct().size == rows.size) { "duplicate update dismissal" }
    return rows.sortedBy { it.first }.joinToString("\n") { (label, version) -> "$label\t$version" }
}
