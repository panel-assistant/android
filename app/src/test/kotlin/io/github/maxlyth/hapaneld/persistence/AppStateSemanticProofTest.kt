package io.github.maxlyth.hapaneld.persistence

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppStateSemanticProofTest {
    private val config = AppStateDigestRow("config", "dark_mode", "boolean", "1", 10L)
    private val runtime = AppStateDigestRow("controller-state", "volume", "int", "4", 20L)
    private val ordinaryDefault = GuardDbSettingDefault("ordinary_key", "string", "default-value")

    @Test fun `ordered digest is independent of query order but covers every durable row`() {
        val forward = canonicalAppStateSemanticProof(listOf(config, runtime))
        val reverse = canonicalAppStateSemanticProof(listOf(runtime, config))
        assertEquals(forward, reverse)
        assertEquals(2L, forward.count)
        assertNotEquals(
            forward.orderedSha256,
            canonicalAppStateSemanticProof(listOf(config, runtime.copy(valueText = "5"))).orderedSha256,
        )
    }

    @Test fun `settings digest covers config semantics but not timestamps or unrelated namespaces`() {
        val baseline = canonicalAppStateSemanticProof(listOf(config, runtime))
        assertEquals(
            baseline.settingsSha256,
            canonicalAppStateSemanticProof(listOf(config.copy(updatedAt = 999L), runtime.copy(valueText = "5"))).settingsSha256,
        )
        assertNotEquals(
            baseline.settingsSha256,
            canonicalAppStateSemanticProof(listOf(config.copy(valueText = "0"), runtime)).settingsSha256,
        )
    }

    @Test fun `ordered digest covers timestamp preservation while settings semantic digest does not`() {
        val baseline = canonicalAppStateSemanticProof(listOf(config, runtime))
        val changed = canonicalAppStateSemanticProof(listOf(config.copy(updatedAt = 11L), runtime))
        assertNotEquals(baseline.orderedSha256, changed.orderedSha256)
        assertEquals(baseline.settingsSha256, changed.settingsSha256)
    }

    @Test fun `absent ordinary setting and explicit registry default have identical effective semantics`() {
        val absent = canonicalAppStateSemanticProof(listOf(runtime), listOf(ordinaryDefault))
        val explicit = canonicalAppStateSemanticProof(
            listOf(runtime, AppStateDigestRow("config", ordinaryDefault.key, ordinaryDefault.type,
                ordinaryDefault.value, 99L)),
            listOf(ordinaryDefault),
        )
        assertEquals(absent.settingsSha256, explicit.settingsSha256)
        assertNotEquals(absent.orderedSha256, explicit.orderedSha256)
    }

    @Test fun `registry default change changes absent setting semantics`() {
        val baseline = canonicalAppStateSemanticProof(listOf(runtime), listOf(ordinaryDefault))
        val changedDefault = canonicalAppStateSemanticProof(
            listOf(runtime),
            listOf(ordinaryDefault.copy(value = "new-default")),
        )
        assertNotEquals(baseline.settingsSha256, changedDefault.settingsSha256)
    }

    @Test fun `HA exposure defaults including read only telemetry are sealed effective settings`() {
        val defaults = authoritativeGuardDbSettingDefaults().associateBy(GuardDbSettingDefault::key)
        SettingsRegistry.haCapable().forEach { spec ->
            val exposure = defaults[SettingsRegistry.exposureKey(spec)]
            assertEquals("boolean", exposure?.type)
            assertEquals(if (spec.haExposedByDefault) "1" else "0", exposure?.value)
        }
        val readOnly = SettingsRegistry.haCapable().first { it.readOnly }
        val exposureDefault = requireNotNull(defaults[SettingsRegistry.exposureKey(readOnly)])
        val absent = canonicalAppStateSemanticProof(listOf(runtime), listOf(exposureDefault))
        val explicit = canonicalAppStateSemanticProof(
            listOf(runtime, AppStateDigestRow(
                "config", exposureDefault.key, "boolean", exposureDefault.value, 99L,
            )),
            listOf(exposureDefault),
        )
        assertEquals(absent.settingsSha256, explicit.settingsSha256)
        assertNotEquals(
            absent.settingsSha256,
            canonicalAppStateSemanticProof(
                listOf(runtime), listOf(exposureDefault.copy(value = if (exposureDefault.value == "1") "0" else "1")),
            ).settingsSha256,
        )
    }

    @Test fun `checked in settings authority exactly matches registered effective defaults`() {
        val expected = canonicalGuardDbSettingsAuthority()
        // Source-text reason: the shipped settings authority file is a data contract read by the guard.
        val checkedIn = File("src/main/assets/guard-db-settings-authority-v2").readBytes()
        assertArrayEquals(expected, checkedIn)
    }

    @Test fun `legacy camera snapshot exposure keeps its absent false and opted in semantics`() {
        val absent = canonicalAppStateSemanticProof(listOf(runtime))
        fun withExposure(value: String) = canonicalAppStateSemanticProof(
            listOf(runtime, AppStateDigestRow("config", "ha_expose_camera_enabled", "boolean", value, 99L)),
        )
        assertEquals(absent.settingsSha256, withExposure("0").settingsSha256)
        assertNotEquals(absent.settingsSha256, withExposure("1").settingsSha256)
        assertNotEquals(absent.orderedSha256, withExposure("0").orderedSha256)
    }

    @Test fun `present ordinary override remains exact instead of collapsing to its default`() {
        fun proof(value: String) = canonicalAppStateSemanticProof(
            listOf(runtime, AppStateDigestRow("config", ordinaryDefault.key, ordinaryDefault.type, value, 10L)),
            listOf(ordinaryDefault),
        )
        assertNotEquals(proof(ordinaryDefault.value).settingsSha256, proof("override").settingsSha256)
        assertNotEquals(proof("override").settingsSha256, proof("different-override").settingsSha256)
    }

    @Test fun `dynamic config rows outside registry remain exact semantics`() {
        fun proof(value: String) = canonicalAppStateSemanticProof(
            listOf(runtime, AppStateDigestRow("config", "dynamic_extra", "string", value, 10L)),
            listOf(ordinaryDefault),
        )
        assertNotEquals(proof("first").settingsSha256, proof("second").settingsSha256)
    }

    @Test fun `settings authority has one exact sorted lowercase hex grammar and golden vector`() {
        val defaults = listOf(
            GuardDbSettingDefault("z|é", "string", "line\n|"),
            GuardDbSettingDefault("a", "boolean", "1"),
        )
        val expected =
            "S2\n" +
                "61|626f6f6c65616e|31\n" +
                "7a7cc3a9|737472696e67|6c696e650a7c\n"

        val canonical = canonicalGuardDbSettingsAuthority(defaults)
        assertEquals(expected, canonical.toString(Charsets.US_ASCII))
        assertArrayEquals(canonical, canonicalGuardDbSettingsAuthority(defaults.reversed()))
        canonical.toString(Charsets.US_ASCII).lineSequence().drop(1).filter(String::isNotEmpty).forEach { line ->
            assertEquals(3, line.split('|').size)
            assertEquals(line, line.lowercase())
            assertEquals(true, line.split('|').all { it.matches(Regex("[0-9a-f]*")) })
        }
    }

    @Test fun `Kotlin semantics match the native immutable copy golden`() {
        val proof = canonicalAppStateSemanticProof(
            rows = listOf(
                AppStateDigestRow("config", "alpha", "text", "one", 1L),
                AppStateDigestRow("other", "beta", "text", null, 2L),
            ),
            settingDefaults = listOf(GuardDbSettingDefault("alpha", "string", "default")),
        )

        assertEquals(2L, proof.count)
        assertEquals(
            "9b568de442ed62849bf3990ba7d9415ac0d497c5f9ebd12d7cab32e64407fe31",
            proof.orderedSha256,
        )
        assertEquals(
            "7d472f0c85867fe68bc231b7fe468b2340010eb8f13cd8e8c211708aadf0ffc3",
            proof.settingsSha256,
        )
    }

    @Test fun `settings authority rejects empty duplicate and untyped registries`() {
        assertThrows(IllegalArgumentException::class.java) {
            canonicalGuardDbSettingsAuthority(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalGuardDbSettingsAuthority(listOf(
                GuardDbSettingDefault("same", "string", "one"),
                GuardDbSettingDefault("same", "string", "two"),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            canonicalGuardDbSettingsAuthority(listOf(GuardDbSettingDefault("key", "blob", "value")))
        }
    }
}
