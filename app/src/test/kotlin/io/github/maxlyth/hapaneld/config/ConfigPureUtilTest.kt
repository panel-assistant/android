package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.http.shouldSnapshotConfigSetting
import io.github.maxlyth.hapaneld.http.projectConfigSnapshot
import io.github.maxlyth.hapaneld.http.preserveUnconfiguredZigbeeOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBundleTest {
    @Test fun transientSettingsAreSkippedBeforeTheirLiveValueIsEvaluated() {
        val evaluated = mutableListOf<String>()

        projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = true,
            effectiveValue = { spec -> evaluated += spec.key; spec.default },
        )

        assertFalse("cpu_governor is transient and must never be probed for a snapshot", "cpu_governor" in evaluated)
        assertTrue("non-transient controller values still belong in the snapshot", "touch_sound" in evaluated)
    }

    @Test fun unconfiguredZigbeeIntentIsOmittedInsteadOfBecomingExplicitOff() {
        assertFalse(shouldSnapshotConfigSetting("zigbee_router", zigbeeRouterConfigured = false))
        assertTrue(shouldSnapshotConfigSetting("zigbee_router", zigbeeRouterConfigured = true))
        assertTrue(shouldSnapshotConfigSetting("keep_awake", zigbeeRouterConfigured = false))

        val fullBackupConfig = projectConfigSnapshot(
            specs = SettingsRegistry.settable(),
            zigbeeRouterConfigured = false,
            excludedKeys = setOf("dashboard_entity_overrides"),
            effectiveValue = { it.default },
        )
        assertFalse(fullBackupConfig.containsKey("zigbee_router"))
        assertFalse(fullBackupConfig.containsKey("dashboard_entity_overrides"))
        assertTrue(fullBackupConfig.containsKey("keep_awake"))

        val legacyRestore = linkedMapOf("zigbee_router" to "false", "keep_awake" to "true")
        assertTrue(preserveUnconfiguredZigbeeOwnership(legacyRestore, targetConfigured = false))
        assertFalse(legacyRestore.containsKey("zigbee_router"))
        assertEquals("true", legacyRestore["keep_awake"])

        val explicitOn = linkedMapOf("zigbee_router" to "true")
        assertFalse(preserveUnconfiguredZigbeeOwnership(explicitOn, targetConfigured = false))
        assertEquals("true", explicitOn["zigbee_router"])

        val configuredTarget = linkedMapOf("zigbee_router" to "false")
        assertFalse(preserveUnconfiguredZigbeeOwnership(configuredTarget, targetConfigured = true))
        assertEquals("false", configuredTarget["zigbee_router"])
    }
    @Test fun roundTripsThroughSerialize() {
        val b = ConfigBundle.fromValues(
            values = mapOf("mqtt_broker" to "tcp://ha:1883", "friendly_name" to "Front Room"),
            schema = 1, exportedAt = "2026-06-29T00:00:00Z", exportedBy = "test",
        )
        val parsed = ConfigBundle.parse(b.serialize())
        assertNotNull(parsed)
        assertEquals(b, parsed)
    }

    @Test fun valuesAreSortedForStableOutput() {
        val json = ConfigBundle.fromValues(mapOf("z" to "1", "a" to "2")).serialize()
        assertTrue(json.indexOf("\"a\"") < json.indexOf("\"z\""))
    }

    @Test fun escapesAndUnescapes() {
        val v = mapOf("k" to "a\\b\"c\nd")
        val parsed = ConfigBundle.parse(ConfigBundle.fromValues(v).serialize())!!
        assertEquals("a\\b\"c\nd", parsed.values["k"])
    }

    @Test fun credentialBundlePreservesPasswordWhitespaceAndOauthExpiry() {
        val values = mapOf(
            "mqtt_password" to "  exact password bytes  ",
            "ha_token_expiry" to "2345678901",
        )
        val parsed = ConfigBundle.parse(ConfigBundle.fromValues(values).serialize())!!

        assertEquals("  exact password bytes  ", parsed.values["mqtt_password"])
        assertEquals("2345678901", parsed.values["ha_token_expiry"])
        assertEquals(
            "  exact password bytes  ",
            (SettingValue.validate(
                SettingsRegistry.spec("mqtt_password")!!,
                parsed.values.getValue("mqtt_password"),
            ) as Validation.Ok).normalized,
        )
    }

    @Test fun autoSleepSurvivesLegacyBundleRoundTripAndMigration() {
        val legacy = ConfigBundle.fromValues(mapOf("auto_sleep" to "true"), schema = 2)
        val parsed = ConfigBundle.parse(legacy.serialize())!!
        val (migrated, warnings) = Migrations.migrate(parsed.schema, parsed.values)

        assertTrue(warnings.isEmpty())
        assertEquals("true", migrated["auto_sleep"])
        assertEquals(
            Validation.Ok("true"),
            SettingValue.validate(requireNotNull(SettingsRegistry.spec("auto_sleep")), migrated.getValue("auto_sleep")),
        )
    }

    @Test fun malformedReturnsNull() {
        assertNull(ConfigBundle.parse("not json"))
        assertNull(ConfigBundle.parse("{\"kind\":"))
        assertNull(ConfigBundle.parse(ConfigBundle.fromValues(mapOf("a" to "1")).serialize() + " trailing"))
    }

    @Test fun missingKeysFallBackToDefaults() {
        val parsed = ConfigBundle.parse("{}")!!
        assertEquals("", parsed.kind)
        assertEquals(0, parsed.schema)
        assertEquals("", parsed.exportedAt)
        assertEquals("", parsed.exportedBy)
        assertTrue(parsed.values.isEmpty())
    }

    @Test fun unknownTopLevelKeysAreIgnored() {
        val parsed = ConfigBundle.parse(
            "{\"kind\":\"ha-paneld-config\",\"schema\":3,\"extra\":\"ignored\",\"values\":{\"a\":\"1\"}}",
        )!!
        assertEquals("ha-paneld-config", parsed.kind)
        assertEquals(3, parsed.schema)
        assertEquals(mapOf("a" to "1"), parsed.values)
    }

    @Test fun wrongTypesRejectTheWholeBundle() {
        // A JSON array, not an object.
        assertNull(ConfigBundle.parse("[]"))
        // kind present but not a string.
        assertNull(ConfigBundle.parse("{\"kind\":5}"))
        // schema present but not a number.
        assertNull(ConfigBundle.parse("{\"schema\":\"2\"}"))
        // values present but not an object.
        assertNull(ConfigBundle.parse("{\"values\":\"x\"}"))
        // values present but a member is not a string.
        assertNull(ConfigBundle.parse("{\"values\":{\"a\":1}}"))
    }
}

class MigrationsTest {
    @Test fun migrationChainHasExactlyOneTransformPerSchemaBoundary() {
        assertEquals(SettingsRegistry.SCHEMA - 1, Migrations.CHAIN.size)
    }

    @Test fun publicV095ConfigFixtureUpgradesInOneStepAndPreservesEveryValue() {
        val fixture = requireNotNull(javaClass.getResource("/fixtures/config-v0.9.5.json"))
            .readText()
        val bundle = requireNotNull(ConfigBundle.parse(fixture))

        assertEquals(ConfigBundle.KIND_CONFIG, bundle.kind)
        assertEquals(2, bundle.schema)
        assertTrue(bundle.exportedBy.contains("0.9.5"))

        val (migrated, warnings) = Migrations.migrate(bundle.schema, bundle.values)

        assertTrue("public 0.9.5 must migrate directly without an unknown intermediate step", warnings.isEmpty())
        // Every value is carried forward untouched EXCEPT the retired sensitivity key, which schema 6
        // replaces with a differently-scaled key rather than redefining in place. Named and asserted
        // rather than loosening the preservation rule for everything.
        val retired = setOf(SettingsRegistry.LEGACY_SENSITIVITY_KEY)
        bundle.values.forEach { (key, value) ->
            if (key !in retired) assertEquals("value changed for $key", value, migrated[key])
        }
        assertEquals("50", bundle.values[SettingsRegistry.LEGACY_SENSITIVITY_KEY])
        assertEquals(
            "the schema-5 neutral must become schema-6 full response, preserving its gain exactly",
            "100",
            migrated[SettingsRegistry.RESPONSE_PERCENT_KEY],
        )
        assertFalse(
            "the retired key must not survive migration; an older build would read it under its own scale",
            migrated.containsKey(SettingsRegistry.LEGACY_SENSITIVITY_KEY),
        )
        assertEquals("false", migrated["auto_sleep"])
        assertTrue("the public fixture must be older than the current schema", SettingsRegistry.SCHEMA > bundle.schema)
        migrated.forEach { (key, value) ->
            val exposure = SettingsRegistry.parseExposure(key)
            if (exposure != null) {
                assertTrue("migrated exposure for $key should be boolean", value == "true" || value == "false")
            } else {
                val spec = requireNotNull(SettingsRegistry.spec(key)) { "fixture key is not a current setting: $key" }
                assertTrue("migrated value for $key should remain valid", SettingValue.validate(spec, value) is Validation.Ok)
            }
        }
    }

    @Test fun sameSchemaNoChange() {
        val (m, w) = Migrations.migrate(SettingsRegistry.SCHEMA, mapOf("a" to "1"))
        assertEquals(mapOf("a" to "1"), m)
        assertTrue(w.isEmpty())
    }

    @Test fun schemaSixAddsCameraDefaultsAndPreservesExplicitChoices() {
        val (defaults, defaultWarnings) = Migrations.migrate(6, mapOf("mqtt_broker" to "tcp://ha:1883"))

        assertEquals("false", defaults["camera_enabled"])
        assertEquals("720p", defaults["camera_resolution"])
        assertEquals("15", defaults["camera_fps"])
        assertEquals("2000", defaults["camera_kbps"])
        assertTrue(defaultWarnings.isEmpty())

        val explicitValues = mapOf(
            "camera_enabled" to "true",
            "camera_resolution" to "1080p",
            "camera_fps" to "24",
            "camera_kbps" to "4000",
        )
        val (explicit, explicitWarnings) = Migrations.migrate(6, explicitValues)

        explicitValues.forEach { (key, value) -> assertEquals(value, explicit[key]) }
        assertTrue(explicitWarnings.isEmpty())
    }

    @Test fun schemaNineAddsZeroGainAndExposureDefaultsAndPreservesExplicitValues() {
        val (defaults, defaultWarnings) = Migrations.migrate(9, mapOf("mqtt_broker" to "tcp://ha:1883"))

        // Both zero values preserve what stores predating the keys already did: unity microphone gain
        // and no bias over the camera's own automatic exposure.
        assertEquals("0", defaults["voice_mic_gain_db"])
        assertEquals("0", defaults["camera_exposure"])
        // Unlike the schema-8 voice keys, neither setting carries an `ha` descriptor. Inventing exposure
        // defaults would publish entities nothing reads.
        assertNull(defaults["${SettingsRegistry.HA_EXPOSE_PREFIX}voice_mic_gain_db"])
        assertNull(defaults["${SettingsRegistry.HA_EXPOSE_PREFIX}camera_exposure"])
        assertTrue(defaultWarnings.isEmpty())

        val (explicit, explicitWarnings) = Migrations.migrate(
            9,
            mapOf("voice_mic_gain_db" to "-6", "camera_exposure" to "1.5"),
        )
        assertEquals("-6", explicit["voice_mic_gain_db"])
        assertEquals("1.5", explicit["camera_exposure"])
        assertTrue(explicitWarnings.isEmpty())
    }

    @Test fun schemaTenAddsDashboardNetworkWarningDefaultAndPreservesExplicitChoice() {
        val (defaults, warnings) = Migrations.migrate(10, emptyMap())
        assertEquals("true", defaults["dashboard_network_warning"])
        assertTrue(warnings.isEmpty())
        val (explicit, explicitWarnings) = Migrations.migrate(
            10, mapOf("dashboard_network_warning" to "false"),
        )
        assertEquals("false", explicit["dashboard_network_warning"])
        assertTrue(explicitWarnings.isEmpty())
    }

    @Test fun schemaSevenAddsVoiceDefaultsAndExposureAndPreservesExplicitChoices() {
        val (defaults, defaultWarnings) = Migrations.migrate(7, mapOf("mqtt_broker" to "tcp://ha:1883"))

        assertEquals("false", defaults["voice_enabled"])
        assertEquals("[\"okay_nabu\"]", defaults["voice_wake_words"])
        assertEquals("{}", defaults["voice_pipelines"])
        assertEquals("voice_recognition", defaults["voice_audio_source"])
        assertEquals("normal", defaults["voice_sensitivity"])
        // Both HA-capable voice entities are not-exposed by default, unlike schema 3's wake_on_wave
        // (LEGACY_DEFAULT_ON_HA_EXPOSURES), which preserved an implicit ON.
        assertEquals("false", defaults["${SettingsRegistry.HA_EXPOSE_PREFIX}voice_enabled"])
        assertEquals("false", defaults["${SettingsRegistry.HA_EXPOSE_PREFIX}voice_state"])
        assertTrue(defaultWarnings.isEmpty())

        val explicitValues = mapOf(
            "voice_enabled" to "true",
            "voice_wake_words" to "[\"hey_jarvis\"]",
            "voice_pipelines" to "{\"hey_jarvis\":\"assist_pipeline_1\"}",
            "voice_audio_source" to "mic",
            "voice_sensitivity" to "high",
            "${SettingsRegistry.HA_EXPOSE_PREFIX}voice_enabled" to "true",
            "${SettingsRegistry.HA_EXPOSE_PREFIX}voice_state" to "true",
        )
        val (explicit, explicitWarnings) = Migrations.migrate(7, explicitValues)

        explicitValues.forEach { (key, value) -> assertEquals(value, explicit[key]) }
        assertTrue(explicitWarnings.isEmpty())
    }

    @Test fun schemaEightAddsAutomaticUiLanguageAndPreservesExplicitChoices() {
        val (defaults, defaultWarnings) = Migrations.migrate(8, mapOf("mqtt_broker" to "tcp://ha:1883"))

        assertEquals(SettingsRegistry.DEFAULT_UI_LANGUAGE, defaults["ui_language"])
        assertTrue(defaultWarnings.isEmpty())

        SettingsRegistry.UI_LANGUAGES.forEach { language ->
            val (explicit, explicitWarnings) = Migrations.migrate(8, mapOf("ui_language" to language))
            assertEquals(language, explicit["ui_language"])
            assertTrue(explicitWarnings.isEmpty())
        }
    }

    @Test fun schemaFourAddsAutomaticMqttAddressFamilyPolicy() {
        val (migrated, warnings) = Migrations.migrate(4, mapOf("mqtt_broker" to "tcp://ha:1883"))

        assertEquals("Automatic", migrated["mqtt_address_family"])
        assertTrue(warnings.isEmpty())
    }

    @Test fun schemaFourPreservesAnExplicitMqttAddressFamilyPolicy() {
        val (migrated, warnings) = Migrations.migrate(
            4,
            mapOf("mqtt_address_family" to "Prefer IPv4"),
        )

        assertEquals("Prefer IPv4", migrated["mqtt_address_family"])
        assertTrue(warnings.isEmpty())
    }

    @Test fun schemaFiveRescalesEveryStoredSensitivityToItsExactGain() {
        // Schema 5 gain was value/50; schema 6 gain is value/100. Doubling is exact, not approximate,
        // for the whole band at or below the old neutral, which is where tuned panels actually sit.
        listOf(0 to "0", 5 to "10", 10 to "20", 25 to "50", 50 to "100").forEach { (legacy, expected) ->
            val (migrated, warnings) = Migrations.migrate(
                5,
                mapOf(SettingsRegistry.LEGACY_SENSITIVITY_KEY to legacy.toString()),
            )

            assertEquals("legacy $legacy", expected, migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
            assertTrue(warnings.isEmpty())
        }
    }

    @Test fun schemaFiveClampsTheRetiredAmplifyingRangeToFullFollow() {
        // Above the old neutral the schema-5 curve amplified past the measured light. Schema 6 has no
        // representation for that, so those panels land on full follow. The lane's one behaviour change.
        listOf("51", "75", "100").forEach { legacy ->
            val (migrated, _) = Migrations.migrate(5, mapOf(SettingsRegistry.LEGACY_SENSITIVITY_KEY to legacy))

            assertEquals("legacy $legacy", "100", migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
        }
    }

    @Test fun schemaFiveGivesAnAbsentSensitivityTheLegacyDefaultResponse() {
        // A bundle without the key was written by a panel sitting on the schema-5 default, whose gain
        // was 1.0. Leaving it absent would hand the panel the schema-6 default instead, which is half
        // that — a silent behaviour change on import.
        val (migrated, _) = Migrations.migrate(5, mapOf("mqtt_broker" to "tcp://ha:1883"))

        assertEquals("100", migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
    }

    @Test fun schemaFiveRejectsAMalformedSensitivityRatherThanCarryingItForward() {
        val (migrated, _) = Migrations.migrate(5, mapOf(SettingsRegistry.LEGACY_SENSITIVITY_KEY to "balanced"))

        assertEquals("100", migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
    }

    @Test fun schemaFiveNeverCarriesTheRetiredKeyForwardUnderTheNewScale() {
        // The whole reason schema 6 uses a new key: an older build tolerates a newer bundle and keeps
        // the values it recognises, so a surviving retired key would be read on the schema-5 scale where
        // the same number means double the response.
        val (migrated, _) = Migrations.migrate(5, mapOf(SettingsRegistry.LEGACY_SENSITIVITY_KEY to "10"))

        assertFalse(migrated.containsKey(SettingsRegistry.LEGACY_SENSITIVITY_KEY))
        assertEquals("20", migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
    }

    @Test fun schemaFiveLeavesAnAlreadyMigratedResponseAlone() {
        // The live store presents every registered setting through its default, so the new key is always
        // present on that path and this transform must not act. Only a genuine pre-schema-6 bundle,
        // which cannot contain the new key, gets a value filled in here.
        val (migrated, _) = Migrations.migrate(
            5,
            mapOf(
                SettingsRegistry.RESPONSE_PERCENT_KEY to "35",
                SettingsRegistry.LEGACY_SENSITIVITY_KEY to "10",
            ),
        )

        assertEquals("35", migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
        assertFalse(migrated.containsKey(SettingsRegistry.LEGACY_SENSITIVITY_KEY))
    }

    @Test fun theRescaleClampsHighRatherThanOverflowingLow() {
        // An imported value is arbitrary text that merely parses as an Int. Doubling before bounding
        // overflows to a negative number for a large one, which would then clamp LOW - the opposite of
        // saturation, and a silently dark panel rather than a bright one.
        assertEquals(100, Migrations.rescaleSensitivity(Int.MAX_VALUE))
        assertEquals(100, Migrations.rescaleSensitivity(Int.MAX_VALUE / 2 + 1))
        assertEquals(100, Migrations.rescaleSensitivity(1_500_000_000))
        assertEquals(0, Migrations.rescaleSensitivity(Int.MIN_VALUE))
        assertEquals(0, Migrations.rescaleSensitivity(-1))

        val (migrated, _) = Migrations.migrate(
            5,
            mapOf(SettingsRegistry.LEGACY_SENSITIVITY_KEY to Int.MAX_VALUE.toString()),
        )
        assertEquals("100", migrated[SettingsRegistry.RESPONSE_PERCENT_KEY])
    }

    @Test fun newerThanCurrentToleratesWithWarning() {
        val (m, w) = Migrations.migrate(SettingsRegistry.SCHEMA + 5, mapOf("a" to "1"))
        assertEquals(mapOf("a" to "1"), m)
        assertTrue(w.any { it.contains("newer") })
    }

    @Test fun schemaOneAddsTheBackwardCompatibleAutomaticBrightnessFloor() {
        val (migrated, warnings) = Migrations.migrate(1, mapOf("a" to "1"))

        assertEquals("1", migrated["a"])
        assertEquals("4", migrated["auto_brightness_minimum_percent"])
        assertTrue(warnings.isEmpty())
    }

    @Test fun schemaOnePreservesAnExplicitAutomaticBrightnessFloor() {
        val (migrated, warnings) = Migrations.migrate(
            1,
            mapOf("auto_brightness_minimum_percent" to "25"),
        )

        assertEquals("25", migrated["auto_brightness_minimum_percent"])
        assertTrue(warnings.isEmpty())
    }

    @Test fun schemaTwoAddsOnlyTheDeviceLocalAutoSleepSwitch() {
        val (migrated, warnings) = Migrations.migrate(2, mapOf("a" to "1"))

        assertEquals("false", migrated["auto_sleep"])
        assertFalse(migrated.containsKey("auto_sleep_source_mode"))
        assertFalse(migrated.containsKey("auto_sleep_ha_entities"))
        assertTrue(warnings.isEmpty())
    }

    @Test fun schemaThreeBundlePreservesLegacyImplicitWakeAndExposureDefaults() {
        val (migrated, warnings) = Migrations.migrate(3, emptyMap())

        assertEquals("true", migrated["wake_on_wave"])
        SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
            assertEquals("true", migrated["${SettingsRegistry.HA_EXPOSE_PREFIX}$key"])
        }
        assertTrue(warnings.isEmpty())
    }
}

class ConfigDiffTest {
    @Test fun reportsOnlyChangedKeys() {
        val current = mapOf("a" to "1", "b" to "2")
        val candidate = mapOf("a" to "1", "b" to "3", "c" to "9")
        val d = ConfigDiff.diff(current, candidate)
        assertEquals(2, d.size)
        assertEquals(ConfigDiff.Change("b", "2", "3"), d[0])
        assertEquals(ConfigDiff.Change("c", null, "9"), d[1])
    }
}

class RevisionRingTest {
    @Test fun noEvictionUnderCap() {
        assertTrue(RevisionRing.toEvict(listOf(1L, 2L, 3L), max = 20).isEmpty())
    }

    @Test fun evictsOldestWhenAddingOverflows() {
        val existing = (1L..20L).toList()
        assertEquals(listOf(1L), RevisionRing.toEvict(existing, max = 20))
    }

    @Test fun evictsMultipleWhenFarOver() {
        val existing = (1L..25L).toList()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), RevisionRing.toEvict(existing, max = 20))
    }
}
