package io.panelassistant.android.config

/**
 * Ordered schema-migration chain for config bundles. Each [Migration] upgrades a values map from
 * schema N to N+1 (rename a key, retype a value, supply a default for a newly-added setting). Pure.
 *
 * Policy (a fleet updates asynchronously, so be lenient):
 *  - older bundle (schema < current): run the chain to upgrade it to the current shape.
 *  - newer bundle (schema > current): tolerate-and-warn; apply what is recognised, never hard-fail.
 */
object Migrations {

    fun interface Migration {
        /** Transform the values map in place from schema N to N+1. */
        fun apply(values: MutableMap<String, String>)
    }

    /**
     * CHAIN[i] migrates schema (i+1) → (i+2). When the persisted shape next changes, bump
     * [SettingsRegistry.SCHEMA] and append the transform here.
     */
    val CHAIN: List<Migration> = listOf(
        Migration { values -> values.putIfAbsent("auto_brightness_minimum_percent", "4") },
        Migration { values ->
            values.putIfAbsent("auto_sleep", "false")
        },
        Migration { values ->
            // Schema-3 bundles encoded the old implicit defaults. Preserve those when absent; new
            // schema-4 stores use the safer registry defaults directly.
            values.putIfAbsent("wake_on_wave", "true")
            SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
                values.putIfAbsent("${SettingsRegistry.HA_EXPOSE_PREFIX}$key", "true")
            }
        },
        Migration { values ->
            values.putIfAbsent("mqtt_address_family", SettingsRegistry.DEFAULT_MQTT_ADDRESS_FAMILY)
        },
        // Schema 5 stored the adaptive response on a scale where 50 was neutral and everything above it
        // amplified beyond the measured light. Schema 6 retires that key for a new one holding the
        // fraction of the decided deviation that is applied, so full response is 100 and the amplifying
        // half is gone. Doubling carries the old choice exactly up to the old neutral; above it there is
        // no equivalent, so it lands on full response.
        //
        // Only fills the new key when the bundle does not already carry one, which is what separates a
        // genuine pre-schema-6 bundle from the live store. The live store presents every registered
        // setting through its default, so the new key is always present there and this transform must
        // not act; Config.migrateLiveStore owns that path, where a stored value can be told apart from
        // a defaulted one. The retired key is dropped so an upgraded bundle cannot reintroduce it.
        Migration { values ->
            val legacy = values.remove(SettingsRegistry.LEGACY_SENSITIVITY_KEY)?.trim()?.toIntOrNull()
            values.putIfAbsent(
                SettingsRegistry.RESPONSE_PERCENT_KEY,
                // Absent means the exporting panel sat on the schema-5 default, whose equivalent is full
                // response. Leaving it absent would hand the panel the new default, a weaker response.
                rescaleSensitivity(legacy ?: SettingsRegistry.LEGACY_NEUTRAL_SENSITIVITY).toString(),
            )
        },
        Migration { values ->
            values.putIfAbsent("camera_enabled", "false")
            values.putIfAbsent("camera_resolution", "720p")
            values.putIfAbsent("camera_fps", "15")
            values.putIfAbsent("camera_kbps", "2000")
        },
        // Schema 8 adds the Voice settings group. The voice settings carry no `ha` descriptor, so they
        // need no exposure defaults.
        Migration { values ->
            values.putIfAbsent("voice_enabled", "false")
            values.putIfAbsent("voice_wake_words", "[\"okay_nabu\"]")
            values.putIfAbsent("voice_pipelines", "{}")
            values.putIfAbsent("voice_audio_source", "voice_recognition")
            values.putIfAbsent("voice_sensitivity", "normal")
        },
        // Schema 9 adds the per-panel interface-language preference. `auto` preserves the existing
        // locale-selection behaviour; an explicit value must survive unchanged when a bundle advances.
        Migration { values ->
            values.putIfAbsent("ui_language", SettingsRegistry.DEFAULT_UI_LANGUAGE)
        },
        // Schema 10 adds the Assist pre-amplification gain and camera exposure bias. Neither carries an
        // `ha` descriptor, so they need no exposure defaults. Zero preserves both prior behaviours: unity
        // gain and the camera's own automatic exposure.
        Migration { values ->
            values.putIfAbsent("voice_mic_gain_db", "0")
            values.putIfAbsent("camera_exposure", "0")
        },
        Migration { values -> values.putIfAbsent("dashboard_network_warning", "true") },
        Migration { values -> values.putIfAbsent("auto_sleep_touch_delay_seconds", "30") },
        Migration { values -> values.putIfAbsent("auto_brightness_maximum_percent", "100") },
        Migration { values -> values.keys.removeAll(SettingsRegistry.RETIRED_KEYS) },
    )

    /**
     * A schema-5 sensitivity to its schema-6 equivalent: exact up to the old neutral, full response above.
     *
     * Bounded BEFORE scaling, not after. An imported value is arbitrary attacker- or corruption-supplied
     * text that merely parses as an `Int`, and doubling it first lets a large value overflow to a
     * negative number that then clamps LOW — the opposite of the intended saturation.
     */
    internal fun rescaleSensitivity(legacy: Int): Int = legacy.coerceIn(0, LEGACY_FULL_RESPONSE) * 2

    /** The schema-5 value that already meant full response; everything above it amplified past it. */
    private const val LEGACY_FULL_RESPONSE = 50

    /**
     * Upgrade [values] from [fromSchema] to [SettingsRegistry.SCHEMA]. Returns the migrated map plus
     * any warnings (e.g. a newer-than-current bundle). Never throws on version mismatch.
     */
    fun migrate(fromSchema: Int, values: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val current = SettingsRegistry.SCHEMA
        val warnings = mutableListOf<String>()
        val m = LinkedHashMap(values)
        when {
            fromSchema == current -> Unit
            fromSchema < current -> {
                // Apply CHAIN[fromSchema-1 .. current-2] in order.
                for (i in (fromSchema - 1) until (current - 1)) {
                    CHAIN.getOrNull(i)?.apply(m)
                        ?: warnings.add("no migration registered for schema ${i + 1}→${i + 2}; values left as-is")
                }
            }
            else -> warnings.add(
                "bundle schema $fromSchema is newer than this panel (schema $current); " +
                    "unrecognised-shape values may be ignored",
            )
        }
        m.keys.removeAll(SettingsRegistry.RETIRED_KEYS)
        return m to warnings
    }
}
