package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.Migrations
import io.github.maxlyth.hapaneld.config.SettingSpec
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.ktor.http.Parameters

/** Result of validating a direct config POST before any preference or controller mutation. */
internal sealed class ConfigPostParameters {
    data class Ok(val values: Parameters) : ConfigPostParameters()
    data class Bad(val reason: String) : ConfigPostParameters()
}

/**
 * Validate and normalize every direct-config value in one pass. Historically the bespoke route
 * normalized only panel_id while malformed booleans became false, numeric values were silently
 * clamped/ignored, and large identity strings could be repeated into every MQTT discovery payload.
 * Keeping this admission step ahead of applyBatch gives form, JSON and registry command paths the
 * same schema semantics and ensures a bad field cannot produce a partial commit.
 *
 * [caps] admits capability-gated ENUM choices (see [SettingSpec.optionRequires]). It defaults to an
 * all-false snapshot so an unparameterized call is fail-closed: a gated choice is refused rather than
 * waved through by a caller that had no snapshot to offer.
 */
internal fun normalizeConfigPostParameters(
    raw: Parameters,
    caps: Capabilities = Capabilities(),
): ConfigPostParameters {
    val normalized = Parameters.build {
        for (rawName in raw.names()) {
            val all = raw.getAll(rawName).orEmpty()
            if (all.size != 1) return ConfigPostParameters.Bad("$rawName: expected one value")
            val rawValue = all.single()
            // The retired sensitivity key is accepted on its old scale and carried onto the new one, the
            // same way the migration carries a stored value. Without this a script or automation written
            // against the previous release does not merely lose that key: this admission step is atomic,
            // so the whole request is refused and every other setting in it is dropped too.
            val renamed = rawName == SettingsRegistry.LEGACY_SENSITIVITY_KEY
            val name = if (renamed) SettingsRegistry.RESPONSE_PERCENT_KEY else rawName
            val value = if (renamed) {
                rawValue.trim().toIntOrNull()?.let { Migrations.rescaleSensitivity(it).toString() } ?: rawValue
            } else {
                rawValue
            }
            val spec = SettingsRegistry.spec(name)
            val accepted = when {
                spec != null -> {
                    if (spec.readOnly) return ConfigPostParameters.Bad("$name: read-only")
                    if (name in SettingsRegistry.directPostExcludedKeys) {
                        val owner = if (name in SettingsRegistry.machineOwnedKeys) {
                            "machine-owned state"
                        } else {
                            "specialized Entities API state"
                        }
                        return ConfigPostParameters.Bad("$name: $owner is not directly postable")
                    }
                    when (val result = SettingValue.validate(spec, value)) {
                        is Validation.Ok -> {
                            // A choice can be valid vocabulary yet unavailable on this hardware. Refuse it
                            // here, where the failure is one explicit 400, rather than letting it persist
                            // and be silently coerced back on the next read.
                            if (spec.optionRequires.isNotEmpty() && result.normalized !in spec.optionsFor(caps)) {
                                return ConfigPostParameters.Bad(
                                    "$name: ${result.normalized} is not available on this panel",
                                )
                            }
                            result.normalized
                        }
                        is Validation.Bad -> return ConfigPostParameters.Bad(result.reason)
                    }
                }
                name.startsWith(SettingsRegistry.HA_EXPOSE_PREFIX) -> {
                    if (SettingsRegistry.parseExposure(name) == null) {
                        return ConfigPostParameters.Bad("$name: unknown exposure setting")
                    }
                    SettingValue.parseBool(value)?.toString()
                        ?: return ConfigPostParameters.Bad("$name: expected a boolean")
                }
                name == "ha_token_expiry" -> {
                    val expiry = value.trim().toLongOrNull()
                        ?: return ConfigPostParameters.Bad("ha_token_expiry: expected an integer")
                    if (expiry < 0L) return ConfigPostParameters.Bad("ha_token_expiry: must be ≥ 0")
                    expiry.toString()
                }
                // The handover keys are a machine channel between the Panel Assistant integration and
                // this panel, not settings. They are deliberately absent from SettingsRegistry so they
                // can never appear on the Configure page, in the settings catalogue, or in a config
                // bundle, exactly as `http_allowed_hosts` is.
                name == "ha_setup_handover" -> {
                    SettingValue.parseBool(value)?.toString()
                        ?: return ConfigPostParameters.Bad("ha_setup_handover: expected a boolean")
                }
                name == "ha_url_handover" -> {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) {
                        ""
                    } else {
                        io.github.maxlyth.hapaneld.config.normalizeHttpOriginUrl(trimmed)
                            ?: return ConfigPostParameters.Bad(
                                "ha_url_handover: expected an http:// or https:// URL with no " +
                                    "embedded credentials",
                            )
                    }
                }
                // Refused from the network outright. The panel's own probe decides this and appends it
                // after admission; accepting it here would let the party that supplies an address also
                // declare that address reachable.
                name == "ha_url_handover_reason" ->
                    return ConfigPostParameters.Bad(
                        "ha_url_handover_reason: the panel's own verification writes this",
                    )
                name == "http_allowed_hosts" -> {
                    val trimmed = value.trim()
                    if (trimmed.length > 4_096) {
                        return ConfigPostParameters.Bad("http_allowed_hosts: must be at most 4096 characters")
                    }
                    val hosts = trimmed.split(Regex("[\\s,]+")).filter(String::isNotEmpty)
                    if (hosts.size > 128 || hosts.any { it.length > 253 || it.any(Char::isWhitespace) }) {
                        return ConfigPostParameters.Bad("http_allowed_hosts: expected at most 128 host names")
                    }
                    hosts.distinct().joinToString(" ")
                }
                else -> return ConfigPostParameters.Bad("$name: unknown setting")
            }
            append(name, accepted)
        }
    }
    return ConfigPostParameters.Ok(normalized)
}
