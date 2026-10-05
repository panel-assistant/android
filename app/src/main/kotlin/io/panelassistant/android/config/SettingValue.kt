package io.panelassistant.android.config

import java.util.Locale

/**
 * Pure typed validation + normalization of a raw inbound value against a [SettingSpec].
 * Returns the canonical string to persist, or a reason the value was rejected. Type/range/enum
 * checks happen here; a spec may layer a custom [SettingSpec.validate] on top (run last).
 */
object SettingValue {

    fun validate(spec: SettingSpec, raw: String): Validation {
        // Passwords are opaque credential bytes represented as a String. Leading/trailing spaces are
        // valid MQTT password content and must survive export/import/restore exactly; all other setting
        // types retain their historical whitespace normalization.
        val v = if (spec.type == SettingType.PASSWORD) raw else raw.trim()
        val typed: Validation = when (spec.type) {
            SettingType.BOOL -> {
                val b = parseBool(v) ?: return Validation.Bad("${spec.key}: expected a boolean")
                Validation.Ok(b.toString())
            }
            SettingType.INT -> {
                val n = v.toLongOrNull() ?: return Validation.Bad("${spec.key}: expected an integer")
                rangeCheck(spec, n.toDouble()) ?: Validation.Ok(n.toString())
            }
            SettingType.LONG -> {
                val n = v.toLongOrNull() ?: return Validation.Bad("${spec.key}: expected an integer")
                rangeCheck(spec, n.toDouble()) ?: Validation.Ok(n.toString())
            }
            SettingType.FLOAT -> {
                val parsed = v.toDoubleOrNull() ?: return Validation.Bad("${spec.key}: expected a number")
                if (!parsed.isFinite()) return Validation.Bad("${spec.key}: expected a finite number")
                rangeCheck(spec, parsed) ?: run {
                    // Config persists FLOAT settings with SharedPreferences.putFloat. Normalize in that
                    // storage domain so an accepted decimal cannot round on write and then fail exact
                    // committed read-back (for example 0.123456789 -> 0.12345679f).
                    val stored = parsed.toFloat()
                    if (!stored.isFinite()) return Validation.Bad("${spec.key}: expected a finite number")
                    Validation.Ok(trimStoredFloat(stored))
                }
            }
            SettingType.ENUM -> {
                // Resolve a retired spelling first, so a bundle written against an older build still
                // imports to the choice it named rather than being rejected as unknown.
                val canonical = spec.aliases.entries
                    .firstOrNull { (retired, _) -> retired.equals(v, ignoreCase = true) }?.value ?: v
                // Case-insensitive match against the declared options; normalize to the declared casing.
                val match = spec.options.firstOrNull { it.equals(canonical, ignoreCase = true) }
                    ?: return Validation.Bad("${spec.key}: must be one of ${spec.options.joinToString(", ")}")
                Validation.Ok(match)
            }
            SettingType.STRING, SettingType.PASSWORD -> {
                if (v.length > spec.maxChars) {
                    return Validation.Bad("${spec.key}: must be at most ${spec.maxChars} characters")
                }
                Validation.Ok(v)
            }
        }
        if (typed is Validation.Bad) return typed
        // Layer the spec's own validator on the type-normalized value.
        return spec.validate((typed as Validation.Ok).normalized)
    }

    private fun rangeCheck(spec: SettingSpec, value: Double): Validation.Bad? {
        spec.min?.let { if (value < it) return Validation.Bad("${spec.key}: must be ≥ ${trimFloat(it)}") }
        spec.max?.let { if (value > it) return Validation.Bad("${spec.key}: must be ≤ ${trimFloat(it)}") }
        return null
    }

    /** Accepts the common truthy/falsey spellings used across the form, MQTT, and provision.sh. */
    fun parseBool(raw: String): Boolean? = when (raw.trim().lowercase(Locale.ROOT)) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> null
    }

    /** Render a double without a trailing ".0" when it is integral (so "5.0" persists as "5"). */
    private fun trimFloat(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Shortest round-trippable spelling of the actual SharedPreferences Float, with integral and
     * negative-zero spellings collapsed to the catalogue's ordinary integer form. */
    private fun trimStoredFloat(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
}
