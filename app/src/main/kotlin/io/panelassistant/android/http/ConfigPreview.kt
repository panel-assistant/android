package io.panelassistant.android.http

import io.panelassistant.android.config.ConfigDiff
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.util.Json

/**
 * Project a config-import preview without turning the dry-run endpoint into a secret read oracle.
 *
 * Every submitted secret is represented as the same redacted change regardless of whether it equals the
 * current value. The key remains visible so the preview can confirm its scope, but neither credential
 * content nor equality is exposed.
 */
internal fun configDryRunJson(
    diff: List<ConfigDiff.Change>,
    skipped: List<String>,
    warnings: List<String>,
    expectedConfig: String,
): String {
    fun q(value: String) = Json.str(value)
    fun array(items: List<String>) = "[" + items.joinToString(",") { q(it) } + "]"
    val changes = diff.joinToString(",") { change ->
        val secret = SettingsRegistry.spec(change.key)?.secret == true
        val from = if (secret) q(REDACTED_CONFIG_VALUE) else change.from?.let(::q) ?: "null"
        val to = if (secret) q(REDACTED_CONFIG_VALUE) else q(change.to)
        "{\"key\":${q(change.key)},\"from\":$from,\"to\":$to}"
    }
    return "{\"status\":\"dry_run\",\"expected_cfg\":${q(expectedConfig)}," +
        "\"changes\":[$changes],\"skipped\":${array(skipped)},\"warnings\":${array(warnings)}}"
}

private const val REDACTED_CONFIG_VALUE = "[redacted]"

/** Public/UI concurrency hashes deliberately exclude credential-bearing settings. */
internal fun configConcurrencyValues(values: Map<String, String>): Map<String, String> =
    values.filterKeys { key -> SettingsRegistry.spec(key)?.secret != true }

/**
 * Secret submissions always produce the same projected entry, including when the guess equals the
 * stored value. This preserves acknowledgement that a secret was submitted without an equality oracle.
 */
internal fun configPreviewDiff(
    current: Map<String, String>,
    candidate: Map<String, String>,
): List<ConfigDiff.Change> {
    val ordinary = ConfigDiff.diff(
        current,
        candidate.filterKeys { key -> SettingsRegistry.spec(key)?.secret != true },
    )
    val secrets = candidate.keys
        .filter { key -> SettingsRegistry.spec(key)?.secret == true }
        .map { key -> ConfigDiff.Change(key, null, REDACTED_CONFIG_VALUE) }
    return (ordinary + secrets).sortedBy { it.key }
}
