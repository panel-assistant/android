package io.panelassistant.android.persistence

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigVaultTest {
    private val dir: File = File.createTempFile("config-vault", "").let {
        it.delete(); it.mkdirs(); it
    }

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    private fun rows(vararg pairs: Pair<String, String?>) = pairs.map { (key, value) ->
        ConfigVault.StateRow("config", key, "string", value, 1_700_000_000_000L)
    }

    private fun export(vararg pairs: Pair<String, String?>, profiles: Map<String, String> = emptyMap()) =
        ConfigVault.Export(rows(*pairs), profiles)

    @Test fun aWrittenGenerationRoundTripsExactly() {
        val original = export(
            "ha_url" to "https://ha.example.test",
            "mqtt_broker" to "tcp://broker.example.test:1883",
            "absent" to null,
            profiles = mapOf("custom-panel.yaml" to "id: custom\nname: Custom"),
        )
        assertNotNull(ConfigVault.write(dir, original, atMillis = 1_000))

        val restored = ConfigVault.newestValid(dir)
        assertEquals(original.rows.sortedBy { it.key }, restored?.rows?.sortedBy { it.key })
        assertEquals(original.profiles, restored?.profiles)
    }

    /** Imported profiles live outside the database, so no whole-database snapshot covers them. */
    @Test fun importedProfilesAreCarried() {
        ConfigVault.write(dir, export("ha_url" to "x", profiles = mapOf("a.yaml" to "one", "b.yaml" to "two")), 1_000)
        assertEquals(mapOf("a.yaml" to "one", "b.yaml" to "two"), ConfigVault.newestValid(dir)?.profiles)
    }

    /** Values legitimately contain the record separators; they must not corrupt the format. */
    @Test fun separatorsAndBackslashesInValuesSurvive() {
        val nasty = "line1\nline2\ttabbed\\slash\r\nend"
        ConfigVault.write(dir, export("tricky" to nasty), 1_000)
        assertEquals(nasty, ConfigVault.newestValid(dir)?.rows?.single()?.valueText)
    }

    /**
     * The critical safety property: a database that failed to open yields no rows, and that must never
     * overwrite good generations with an empty one.
     */
    @Test fun anEmptyExportIsRefused() {
        ConfigVault.write(dir, export("ha_url" to "good"), 1_000)
        assertNull(ConfigVault.write(dir, ConfigVault.Export(emptyList(), emptyMap()), 2_000))
        assertEquals("good", ConfigVault.newestValid(dir)?.rows?.single()?.valueText)
    }

    @Test fun aTruncatedOrCorruptGenerationIsRejectedRatherThanRestored() {
        val file = ConfigVault.write(dir, export("ha_url" to "good"), 1_000)!!
        val good = file.readText()

        assertNull("tampered payload must fail the digest", ConfigVault.decode(good.replace("good", "evil")))
        assertNull("truncation must not parse", ConfigVault.decode(good.substringBeforeLast('\n')))
        assertNull("a foreign format must not parse", ConfigVault.decode("something-else/1\nsha256:0\n"))
        assertNull(ConfigVault.decode(""))
        assertNull(ConfigVault.decode(null))
    }

    @Test fun theNewestValidGenerationWinsAndCorruptOnesAreSkipped() {
        ConfigVault.write(dir, export("ha_url" to "older"), 1_000)
        val newer = ConfigVault.write(dir, export("ha_url" to "newer"), 2_000)!!
        assertEquals("newer", ConfigVault.newestValid(dir)?.rows?.single()?.valueText)

        newer.writeText(newer.readText().replace("newer", "evil")) // corrupt the newest
        assertEquals(
            "must fall back to the newest generation that verifies",
            "older",
            ConfigVault.newestValid(dir)?.rows?.single()?.valueText,
        )
    }

    @Test fun generationsArePrunedNewestFirstAndPartialWritesAreCleanedUp() {
        repeat(6) { index -> ConfigVault.write(dir, export("ha_url" to "v$index"), (index + 1).toLong(), keep = 3) }
        val kept = ConfigVault.generations(dir)
        assertEquals(3, kept.size)
        assertEquals("v5", ConfigVault.newestValid(dir)?.rows?.single()?.valueText)

        File(dir, "config-99.vault.partial").writeText("junk")
        ConfigVault.write(dir, export("ha_url" to "v6"), 7, keep = 3)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".partial") })
    }

    /**
     * The payload is a few tens of kilobytes against a multi-megabyte database, which is the whole
     * reason it can be written unconditionally. Guard the assumption.
     */
    @Test fun aRealisticConfigGenerationStaysTiny() {
        val realistic = ConfigVault.Export(
            (1..200).map {
                ConfigVault.StateRow("config", "setting_$it", "string", "value-$it".repeat(4), 1_700_000_000_000L)
            },
            emptyMap(),
        )
        val file = ConfigVault.write(dir, realistic, 1_000)!!
        assertTrue("200 settings should stay well under 64 KB, was ${file.length()}", file.length() < 64 * 1024)
    }
}
