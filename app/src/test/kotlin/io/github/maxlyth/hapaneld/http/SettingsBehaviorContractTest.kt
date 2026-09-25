package io.github.maxlyth.hapaneld.http

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.LiveSettingApplyResult
import io.github.maxlyth.hapaneld.LiveSettingAuthority
import io.github.maxlyth.hapaneld.LiveSettingRequestOutcome
import io.github.maxlyth.hapaneld.LiveSettingEffectOwner
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.config.SettingSpec
import io.github.maxlyth.hapaneld.config.SettingType
import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.github.maxlyth.hapaneld.persistence.SqliteStatePreferences
import io.github.maxlyth.hapaneld.persistence.StateMutation
import io.github.maxlyth.hapaneld.persistence.StateNamespacePersistence
import io.ktor.http.Parameters
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Behavioural contract for the complete user-postable SettingsRegistry catalogue.
 *
 * This deliberately uses Config's production typed writer and batch boundary through its established
 * SharedPreferences JVM seam. It complements the older source-presence floor: the assertions below
 * require a normalized value to become durable and readable, so a handler that reads and drops a key
 * is observably different from a writer.
 */
class SettingsBehaviorContractTest {
    @Test fun `machine-owned state is exactly excluded from direct user posts`() {
        assertEquals(
            setOf("dashboard_entity_learning_applied"),
            SettingsRegistry.machineOwnedKeys,
        )

        assertEquals(
            setOf(
                "dashboard_entity_overrides",
                "dashboard_entity_auto_static",
                "dashboard_entity_auto_runtime",
            ),
            SettingsRegistry.specializedUserOwnedKeys,
        )

        SettingsRegistry.directPostExcludedKeys.forEach { key ->
            val spec = requireNotNull(SettingsRegistry.spec(key))
            assertFalse(spec.readOnly, "$key remains writable through its declared owner")
            val result = normalizeConfigPostParameters(Parameters.build { append(key, nonDefault(spec)) })
            assertTrue(result is ConfigPostParameters.Bad, "$key must not be accepted from direct HTTP")
        }
    }

    @Test fun `every direct-postable setting accepts and normalizes a non-default value`() {
        assertEquals(
            SettingsRegistry.settable().map { it.key }.toSet() - SettingsRegistry.directPostExcludedKeys,
            SettingsRegistry.directPostable().map { it.key }.toSet(),
        )
        SettingsRegistry.directPostable().forEach { spec ->
            val raw = nonDefault(spec)
            val expected = requireNotNull(SettingValue.validate(spec, raw) as? Validation.Ok).normalized
            val result = normalizeConfigPostParameters(
                Parameters.build { append(spec.key, raw) },
                Capabilities(),
            )
            assertTrue(result is ConfigPostParameters.Ok, "${spec.key} rejected valid sample $raw: $result")
            assertEquals(expected, result.values[spec.key], spec.key)
            assertTrue(expected != spec.default, "${spec.key} sample must exercise a real change")
        }

        val floatSpec = requireNotNull(SettingsRegistry.spec("room_temp_offset"))
        assertEquals("0.12345679", (SettingValue.validate(floatSpec, "0.123456789") as Validation.Ok).normalized)
        assertEquals("0", (SettingValue.validate(floatSpec, "-0.0") as Validation.Ok).normalized)
    }

    @Test fun `specialized user settings reach their real entity owners`() {
        val policyPrefs = memoryPreferences()
        val policyConfig = Config(policyPrefs.instance)
        assertTrue(policyConfig.commitDashboardEntityPromotionPolicy(
            staticRefs = false,
            runtimeRefs = false,
            activeEntityIds = null,
            applied = false,
        ))
        listOf("dashboard_entity_auto_static", "dashboard_entity_auto_runtime").forEach { key ->
            assertEquals("false", policyConfig.getRaw(requireNotNull(SettingsRegistry.spec(key))), key)
            assertEquals(false, policyPrefs.values[key], "$key persistence owner")
        }

        val overridePrefs = memoryPreferences(
            initial = mapOf("ha_url" to "https://ha.example.test", "home_dashboard" to "/lovelace/contract"),
        )
        val overrideConfig = Config(overridePrefs.instance)
        assertTrue(overrideConfig.setDashboardEntityOverrides(mapOf("light.contract" to "pinned")))
        assertEquals(
            "+light.contract",
            overrideConfig.getRaw(requireNotNull(SettingsRegistry.spec("dashboard_entity_overrides"))),
        )
        assertEquals(mapOf("light.contract" to "pinned"), overrideConfig.dashboardEntityOverrides)
    }

    @Test fun `every non-live direct setting reaches Config durability and normalized read-back`() {
        SettingsRegistry.directPostable().filterNot { it.liveApply }.forEach { spec ->
            val normalized = normalizedSample(spec)
            val posted = mapOf(spec.key to normalized)
            val prefs = memoryPreferences()
            val config = Config(prefs.instance)
            val delegated = mutableListOf<String>()

            val committed = config.applyBatch {
                stageDirectConfigRegistryValues(config, posted, setOf(spec.key))
            }
            assertTrue(committed, "${spec.key} did not commit")
            applyDirectConfigDelegatedSettings(posted, setOf(spec.key)) { key, value ->
                delegated += key
                when (key) {
                    "dashboard_entity_learning" -> {
                        config.setDashboardEntityLearningEnabled(value.toBoolean())
                        true
                    }
                    else -> false
                }
            }

            val outcomes = directConfigOrdinaryOutcomes(config, posted, setOf(spec.key))
            assertEquals(setOf(spec.key), outcomes.applied, spec.key)
            assertEquals(emptySet(), outcomes.rejected, spec.key)
            assertEquals(normalized, config.getRaw(spec), "${spec.key} read-back")
            assertEquals(
                if (spec.key == "dashboard_entity_learning") listOf(spec.key) else emptyList(),
                delegated,
                "${spec.key} delegated-owner route",
            )
        }
    }

    @Test fun `the walker persists through the production SQLite preference owner`() {
        val persistence = ContractPersistence()
        val writer = Executors.newSingleThreadExecutor()
        try {
            val config = Config(SqliteStatePreferences(persistence, writer))
            SettingsRegistry.directPostable().filterNot { it.liveApply }.forEach { spec ->
                val normalized = normalizedSample(spec)
                val posted = mapOf(spec.key to normalized)
                assertTrue(config.applyBatch {
                    stageDirectConfigRegistryValues(config, posted, setOf(spec.key))
                }, spec.key)
                applyDirectConfigDelegatedSettings(posted, setOf(spec.key)) { key, value ->
                    val accepted = key == "dashboard_entity_learning"
                    if (accepted) config.setDashboardEntityLearningEnabled(value.toBoolean())
                    accepted
                }
                assertEquals(normalized, config.getRaw(spec), spec.key)
                assertEquals(setOf(spec.key), directConfigOrdinaryOutcomes(
                    config,
                    posted,
                    setOf(spec.key),
                ).applied, spec.key)
            }
            assertTrue(persistence.snapshot.isNotEmpty())
        } finally {
            writer.shutdownNow()
        }
    }

    @Test fun `live settings enter only the shared dispatch lane`() {
        val dispatched = linkedSetOf<String>()
        val authority = LiveSettingAuthority(SettingsRegistry.liveApplyKeys().toSet())
        assertEquals(SettingsRegistry.liveApplyKeys().toSet(), LiveSettingEffectOwner.settingKeys)
        SettingsRegistry.directPostable().forEach { spec ->
            val value = normalizedSample(spec)
            val plan = planDirectConfigMutation(
                posted = mapOf(spec.key to value),
                before = mapOf(spec.key to spec.default),
            )
            val prefs = memoryPreferences()
            val config = Config(prefs.instance)
            config.applyBatch { stageDirectConfigRegistryValues(config, mapOf(spec.key to value), plan.changedKeys) }
            if (spec.liveApply) {
                assertFalse(prefs.values.containsKey(spec.key), "${spec.key} falsely entered ordinary persistence")
            }

            dispatchDirectConfigLiveSettings(plan.changedLive) { key, dispatchedValue ->
                assertEquals(key, LiveSettingEffectOwner.requireFor(key).settingKey)
                assertEquals(
                    LiveSettingRequestOutcome.APPLIED,
                    authority.applyOrQueueOutcome(key, dispatchedValue, null) { ownerKey, ownerValue, _ ->
                        assertEquals(key, ownerKey)
                        assertEquals(dispatchedValue, ownerValue)
                        if (!spec.transient) {
                            assertTrue(config.commitRaw(spec, ownerValue), "$ownerKey persistence owner")
                        }
                        dispatched += ownerKey
                        LiveSettingApplyResult.APPLIED
                    },
                    key,
                )
            }
            if (spec.liveApply) {
                assertEquals(listOf(spec.key to value), plan.changedLive, spec.key)
                assertTrue(spec.key in dispatched, "${spec.key} did not reach the shared dispatch seam")
                if (spec.transient) {
                    assertFalse(prefs.values.containsKey(spec.key), "${spec.key} is declared transient")
                } else {
                    assertEquals(value, config.getRaw(spec), "${spec.key} live read-back")
                }
            } else {
                assertTrue(plan.changedLive.isEmpty(), "${spec.key} falsely entered the live path")
            }
        }
        assertEquals(SettingsRegistry.liveApplyKeys().toSet(), dispatched)
    }

    @Test fun `coupled credential owners clear dependent secrets atomically`() {
        val initial = mapOf<String, Any?>(
            "mqtt_user" to "old-user",
            "mqtt_password" to "old-password",
            "ha_url" to "https://ha.example.test",
            "ha_token" to "old-access",
            "ha_refresh_token" to "old-refresh",
            "ha_token_expiry" to 1234L,
            "ha_client_id" to "old-client",
        )
        val prefs = memoryPreferences(initial = initial)
        val config = Config(prefs.instance)

        assertTrue(config.applyBatch {
            stageDirectCredentialSettings(config, mapOf("mqtt_user" to ""))
        })
        assertEquals("", config.mqttUser)
        assertEquals("", config.mqttPassword)

        assertTrue(config.applyBatch {
            stageDirectCredentialSettings(
                config,
                mapOf(
                    "ha_url" to "",
                    "ha_token" to "",
                    "ha_refresh_token" to "",
                    "ha_token_expiry" to "0",
                    "ha_client_id" to "",
                ),
            )
        })
        assertEquals("", config.haUrl)
        assertEquals("", config.haToken)
        assertEquals("", config.haRefreshToken)
        assertEquals(0L, config.haTokenExpiry)
        assertEquals("", config.haClientId)

        val replacementPrefs = memoryPreferences(initial = initial)
        val replacement = Config(replacementPrefs.instance)
        assertTrue(replacement.applyBatch {
            stageDirectCredentialSettings(replacement, mapOf("ha_token" to "new-access"))
        })
        assertEquals("new-access", replacement.haToken)
        assertEquals("", replacement.haRefreshToken)
        assertEquals(0L, replacement.haTokenExpiry)

        val failedPrefs = memoryPreferences(commitsSucceed = false, initial = initial)
        val failed = Config(failedPrefs.instance)
        assertFalse(failed.applyBatch {
            stageDirectCredentialSettings(failed, mapOf("mqtt_user" to ""))
        })
        assertEquals("old-user", failed.mqttUser)
        assertEquals("old-password", failed.mqttPassword)
    }

    @Test fun `coupled log endpoint reports its stored canonical destination`() {
        val prefs = memoryPreferences()
        val config = Config(prefs.instance)
        val posted = mapOf(
            "log_ship_host" to "udp://collector.lan:1514",
            "log_ship_port" to "1600",
            "log_ship_protocol" to "http",
        )
        val expected = directConfigExpectedReadBack(config, posted)
        assertTrue(config.applyBatch {
            stageDirectConfigRegistryValues(config, posted, posted.keys)
            stageDirectLogShipping(config, posted)
        })
        assertEquals("collector.lan", config.logShipHost)
        assertEquals(1514, config.logShipPort)
        assertEquals("syslog-udp", config.logShipProtocol)
        assertEquals(posted.keys, directConfigOrdinaryOutcomes(config, posted, posted.keys, expected).applied)

        listOf(
            mapOf("log_ship_port" to "9999"),
            mapOf("log_ship_protocol" to "http"),
        ).forEach { partial ->
            val legacy = Config(memoryPreferences(initial = mapOf(
                "log_ship_host" to "udp://legacy.lan:1514",
                "log_ship_port" to 514,
                "log_ship_protocol" to "syslog-tcp",
            )).instance)
            val partialExpected = directConfigExpectedReadBack(legacy, partial)
            assertTrue(legacy.applyBatch {
                stageDirectConfigRegistryValues(legacy, partial, partial.keys)
                stageDirectLogShipping(legacy, partial)
            })
            assertEquals(
                partial.keys,
                directConfigOrdinaryOutcomes(legacy, partial, partial.keys, partialExpected).applied,
                partial.toString(),
            )
        }
    }

    @Test fun `special update channel outcome requires committed read-back`() {
        assertTrue(directUpdateChannelCommitted(true, setOf("update_channel"), "prerelease", "prerelease"))
        assertFalse(directUpdateChannelCommitted(true, setOf("update_channel"), "prerelease", "stable"))
        assertFalse(directUpdateChannelCommitted(false, setOf("update_channel"), "stable", "stable"))
    }

    @Test fun `a durable delegated value is separate from its failed effect receipt`() {
        assertEquals("dashboard_entity_learning_effect", directConfigEffectFailureOwner(true))
        assertEquals("renderer", directConfigEffectFailureOwner(false))
    }

    @Test fun `read-then-drop camera and idle-return shapes are rejected rather than called applied`() {
        listOf(
            "dashboard_idle_return_min",
            "camera_enabled",
            "camera_resolution",
            "camera_fps",
            "camera_kbps",
        ).forEach { key ->
            val spec = requireNotNull(SettingsRegistry.spec(key))
            val posted = mapOf(key to normalizedSample(spec))
            val outcome = directConfigOrdinaryOutcomes(Config(memoryPreferences().instance), posted, setOf(key))
            assertEquals(emptySet(), outcome.applied, key)
            assertEquals(setOf(key), outcome.rejected, "$key dropped write")
        }
    }

    @Test fun `failed persistence never produces an applied outcome`() {
        val spec = requireNotNull(SettingsRegistry.spec("friendly_name"))
        val posted = mapOf(spec.key to normalizedSample(spec))
        val prefs = memoryPreferences(commitsSucceed = false)
        val config = Config(prefs.instance)

        assertFalse(config.applyBatch { stageDirectConfigRegistryValues(config, posted, setOf(spec.key)) })
        val outcome = directConfigOrdinaryOutcomes(config, posted, setOf(spec.key))
        assertEquals(emptySet(), outcome.applied)
        assertEquals(setOf(spec.key), outcome.rejected)
    }

    private fun normalizedSample(spec: SettingSpec): String =
        requireNotNull(SettingValue.validate(spec, nonDefault(spec)) as? Validation.Ok).normalized

    private fun nonDefault(spec: SettingSpec): String {
        val keySamples = mapOf(
            "panel_id" to "Contract Panel",
            "mqtt_broker" to "tcp://192.0.2.1:1883",
            "ha_url" to "https://ha.example.test/",
            "home_dashboard" to "/lovelace/contract",
            "dashboard_package" to "io.github.maxlyth.hapaneld.BUILTIN",
            "launcher_package" to "io.example.launcher",
            "kiosk_companion_packages" to "io.example.companion",
            "tame_vendor_packages" to "io.example.vendor",
            "auto_brightness_ha_entity" to "sensor.contract_lux",
            "voice_wake_words" to "[\"hey_jarvis\"]",
            "voice_pipelines" to "{\"hey_jarvis\":\"contract-pipeline\"}",
            "log_ship_host" to "collector.example.test",
        )
        keySamples[spec.key]?.let { return it }
        return when (spec.type) {
            SettingType.BOOL -> (!spec.default.toBoolean()).toString()
            SettingType.INT, SettingType.LONG, SettingType.FLOAT -> numericSample(spec)
            SettingType.ENUM -> spec.optionsFor(Capabilities()).firstOrNull { option ->
                option != spec.default && SettingValue.validate(spec, option) is Validation.Ok
            } ?: error("${spec.key}: no non-default available enum sample")
            SettingType.PASSWORD -> "contract-secret"
            SettingType.STRING -> "contract-value"
        }
    }

    private fun numericSample(spec: SettingSpec): String {
        val default = spec.default.toDoubleOrNull() ?: 0.0
        val candidates = listOfNotNull(
            spec.min,
            spec.min?.plus(spec.step ?: 1.0),
            spec.max,
            default + (spec.step ?: 1.0),
            default - (spec.step ?: 1.0),
        )
        val candidate = candidates.firstOrNull { value ->
            value != default && (spec.min == null || value >= spec.min) && (spec.max == null || value <= spec.max)
        } ?: error("${spec.key}: no non-default numeric sample")
        return when (spec.type) {
            SettingType.INT, SettingType.LONG -> candidate.toLong().toString()
            else -> candidate.toString()
        }
    }

    private data class MemoryPreferences(
        val instance: SharedPreferences,
        val values: MutableMap<String, Any?>,
    )

    private class ContractPersistence : StateNamespacePersistence {
        var snapshot = linkedMapOf<String, Any>()
        override fun initialize(): Map<String, Any> = snapshot.toMap()
        override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) snapshot.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) snapshot.remove(key) else snapshot[key] = value
            }
            return true
        }
        override fun replace(snapshot: Map<String, Any>): Boolean {
            this.snapshot = LinkedHashMap(snapshot)
            return true
        }
    }

    private fun memoryPreferences(
        commitsSucceed: Boolean = true,
        initial: Map<String, Any?> = emptyMap(),
    ): MemoryPreferences {
        val values = LinkedHashMap(initial)
        val commit = AtomicBoolean(commitsSucceed)
        lateinit var prefs: SharedPreferences
        prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
                "getInt" -> values[args!![0]] as? Int ?: args[1]
                "getLong" -> values[args!![0]] as? Long ?: args[1]
                "getFloat" -> values[args!![0]] as? Float ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> memoryEditor(values, commit::get)
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "SettingsContractPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        return MemoryPreferences(prefs, values)
    }

    private fun memoryEditor(
        values: MutableMap<String, Any?>,
        commitSucceeds: () -> Boolean,
    ): SharedPreferences.Editor {
        val writes = linkedMapOf<String, Any?>()
        val removals = linkedSetOf<String>()
        var clear = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when {
                method.name.startsWith("put") -> editor.also {
                    writes[args!![0] as String] = args[1]
                    removals.remove(args[0])
                }
                method.name == "remove" -> editor.also {
                    removals += args!![0] as String
                    writes.remove(args[0])
                }
                method.name == "clear" -> editor.also { clear = true }
                method.name == "commit" -> commitSucceeds().also { success ->
                    if (success) {
                        if (clear) values.clear()
                        removals.forEach(values::remove)
                        values.putAll(writes)
                    }
                }
                method.name == "apply" -> {
                    if (commitSucceeds()) {
                        if (clear) values.clear()
                        removals.forEach(values::remove)
                        values.putAll(writes)
                    }
                    null
                }
                method.name == "toString" -> "SettingsContractEditor"
                else -> error("unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
