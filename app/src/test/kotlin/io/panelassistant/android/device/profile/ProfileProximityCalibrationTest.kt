package io.panelassistant.android.device.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProximityCalibrationTest {
    private val binary = ProfileProximityCalibration(1, "binary", 1f, 0f, "Attended test fixture")

    private fun document(calibration: ProfileProximityCalibration?) = testProfileDocument().let {
        it.copy(sensors = it.sensors.copy(proximityCalibration = calibration))
    }

    private fun issues(calibration: ProfileProximityCalibration) =
        ProfileValidator.validate(document(calibration), "1.0.0", bundled = false)

    @Test fun `absent baseline stays absent and product metadata cannot synthesize one`() {
        val source = document(null)
        val yaml = ProfileYaml.serialize(source)
        assertFalse(yaml.contains("proximity_calibration"))
        assertNull(ProfileYaml.parse(yaml).document!!.sensors.proximityCalibration)
        assertNull(DataDeviceProfile(source, "120P range 65535", "revision", true).proximityCalibration)
    }

    @Test fun `both binary polarities and both ranged directions round trip and project unchanged`() {
        listOf(binary, binary.copy(clearRaw = 0f, nearRaw = 1f),
            binary.copy(mode = "ranged", clearRaw = 450f, nearRaw = 20f),
            binary.copy(mode = "ranged", clearRaw = 20f, nearRaw = 450f),
        ).forEach { calibration ->
            val source = document(calibration)
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(source))
            assertEquals(emptyList<ProfileIssue>(), parsed.issues)
            assertEquals(source, parsed.document)
            assertEquals(emptyList<ProfileIssue>(), issues(calibration))
            assertEquals(calibration, DataDeviceProfile(source, "lying range metadata", "revision", false).proximityCalibration)
        }
    }

    @Test fun `minimal baseline fills bounded timing and threshold defaults`() {
        val yaml = ProfileYaml.serialize(document(binary)).lineSequence().filterNot {
            it.trimStart().startsWith("near_enter:") || it.trimStart().startsWith("clear_exit:") ||
                it.trimStart().substringBefore(':').endsWith("_ms")
        }.joinToString("\n")
        assertEquals(binary, ProfileYaml.parse(yaml).document!!.sensors.proximityCalibration)
    }

    @Test fun `required evidence endpoints revision and representation cannot be omitted`() {
        listOf("revision", "mode", "clear_raw", "near_raw", "verification").forEach { key ->
            val yaml = ProfileYaml.serialize(document(binary)).lineSequence()
                .filterNot { it.trimStart().startsWith("$key:") }.joinToString("\n")
            val parsed = ProfileYaml.parse(yaml)
            assertNull(key, parsed.document)
            assertTrue(key, parsed.issues.any { it.path == "sensors.proximity_calibration.$key" })
        }
    }

    @Test fun `unknown keys overflow and string numeric values fail strict parsing`() {
        listOf(
            "    maximum_range: 65535\n    revision: 1",
            "    revision: 2147483648",
            "    revision: '1'",
        ).forEach { replacement ->
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(document(binary)).replace("    revision: 1", replacement))
            assertNull(parsed.document)
            assertTrue(parsed.issues.isNotEmpty())
        }
        listOf("1e100", "'0'", "true").forEach { value ->
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(document(binary)).replace("near_raw: 0.0", "near_raw: $value"))
            assertNull(value, parsed.document)
        }
    }

    @Test fun `invalid calibration values are rejected even for directly constructed documents`() {
        listOf(
            binary.copy(revision = 0), binary.copy(mode = "auto"), binary.copy(verification = ""),
            binary.copy(verification = "bad\nreference"), binary.copy(verification = "x".repeat(501)),
            binary.copy(clearRaw = Float.NaN), binary.copy(nearRaw = Float.POSITIVE_INFINITY),
            binary.copy(nearRaw = 1f), binary.copy(clearRaw = 2f),
            binary.copy(mode = "ranged", clearRaw = -Float.MAX_VALUE, nearRaw = Float.MAX_VALUE),
            binary.copy(nearEnter = Float.NaN), binary.copy(clearExit = Float.NaN),
            binary.copy(nearEnter = 1f), binary.copy(clearExit = 0f), binary.copy(clearExit = 0.65f),
            binary.copy(debounceMs = 49), binary.copy(debounceMs = 1001),
            binary.copy(clearArmMs = 199), binary.copy(clearArmMs = 10001),
            binary.copy(debounceMs = 300, clearArmMs = 200, minimumNearMs = 300),
            binary.copy(minimumNearMs = 49), binary.copy(minimumNearMs = 2001),
            binary.copy(minimumNearMs = 100), binary.copy(maximumNearMs = 199),
            binary.copy(maximumNearMs = 10001), binary.copy(maximumNearMs = 200),
            binary.copy(cooldownMs = 199), binary.copy(cooldownMs = 10001),
        ).forEach { invalid ->
            assertTrue(invalid.toString(), issues(invalid).any { it.severity == ProfileIssueSeverity.ERROR })
        }
    }

    @Test fun `legacy format remains implicit while version two absence means presence only`() {
        val old = ProfileYaml.serialize(document(binary))
        assertFalse(old.contains("format_version"))
        assertFalse(old.contains("wave:"))
        val oldParsed = ProfileYaml.parse(old).document!!.sensors.proximityCalibration!!
        assertEquals(1, oldParsed.formatVersion)
        assertNull(oldParsed.wave)

        val presence = binary.copy(formatVersion = 2)
        val yaml = ProfileYaml.serialize(document(presence))
        assertTrue(yaml.contains("format_version: 2"))
        assertFalse(yaml.contains("wave:"))
        assertEquals(presence, ProfileYaml.parse(yaml).document!!.sensors.proximityCalibration)
        assertEquals(emptyList<ProfileIssue>(), issues(presence))
    }

    @Test fun `independent single and double wave anchors round trip without replacing presence`() {
        for (pattern in listOf("single", "double")) {
            for ((clear, near) in listOf(0f to 1f, 1f to 0f)) {
                val baseline = binary.copy(formatVersion = 2, clearRaw = clear, nearRaw = near,
                    wave = ProfileWaveCalibration(pattern, clear, near))
                assertEquals(baseline, ProfileYaml.parse(ProfileYaml.serialize(document(baseline))).document!!.sensors.proximityCalibration)
                assertEquals(emptyList<ProfileIssue>(), issues(baseline))
            }
            val separate = binary.copy(formatVersion = 2, mode = "ranged", clearRaw = 100f, nearRaw = 50f,
                wave = ProfileWaveCalibration(pattern, 98f, 10f, nearEnter = .8f, clearExit = .2f,
                    debounceMs = 200, clearArmMs = 800, minimumNearMs = 300, maximumNearMs = 3000,
                    cooldownMs = 1200, maxInterWaveGapMs = 2100))
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(document(separate)))
            assertEquals(separate, parsed.document!!.sensors.proximityCalibration)
            assertEquals(emptyList<ProfileIssue>(), issues(separate))
            assertEquals(50f, parsed.document!!.sensors.proximityCalibration!!.nearRaw, 0f)
            assertEquals(10f, parsed.document!!.sensors.proximityCalibration!!.wave!!.nearRaw, 0f)
        }
    }

    @Test fun `version and wave constraints fail closed`() {
        val wave = ProfileWaveCalibration("single", 1f, 0f)
        assertTrue(issues(binary.copy(wave = wave)).any { it.path.endsWith(".wave") })
        for (version in listOf(0, 3)) {
            assertTrue(issues(binary.copy(formatVersion = version)).any { it.path.endsWith(".format_version") })
        }
        for (bad in listOf(
            wave.copy(pattern = "triple"), wave.copy(clearRaw = Float.NaN), wave.copy(nearRaw = Float.POSITIVE_INFINITY),
            wave.copy(nearRaw = 1f), wave.copy(clearRaw = 3f), wave.copy(clearRaw = 0f, nearRaw = 1f), wave.copy(nearEnter = 1f), wave.copy(clearExit = 0f),
            wave.copy(clearExit = .8f), wave.copy(nearEnter = Float.NaN), wave.copy(clearExit = Float.NaN),
            wave.copy(debounceMs = 49), wave.copy(debounceMs = 1001), wave.copy(clearArmMs = 199),
            wave.copy(clearArmMs = 10001), wave.copy(debounceMs = 300, clearArmMs = 200, minimumNearMs = 300),
            wave.copy(minimumNearMs = 49), wave.copy(minimumNearMs = 2001), wave.copy(minimumNearMs = 100),
            wave.copy(maximumNearMs = 199), wave.copy(maximumNearMs = 10001), wave.copy(maximumNearMs = 200),
            wave.copy(cooldownMs = 199), wave.copy(cooldownMs = 10001),
            wave.copy(maxInterWaveGapMs = 299), wave.copy(maxInterWaveGapMs = 10001),
        )) {
            assertTrue(bad.toString(), issues(binary.copy(formatVersion = 2, wave = bad)).any { it.path.contains(".wave") })
        }
    }

    @Test fun `nested wave rejects missing observations unknown keys and numeric type coercion`() {
        val yaml = ProfileYaml.serialize(document(binary.copy(formatVersion = 2,
            wave = ProfileWaveCalibration("double", 1f, 0f))))
        for (key in listOf("pattern", "clear_raw", "near_raw")) {
            val missing = yaml.lineSequence().filterNot { it.startsWith("      $key:") }.joinToString("\n")
            val parsed = ProfileYaml.parse(missing)
            assertNull(key, parsed.document)
            assertTrue(key, parsed.issues.any { it.path == "sensors.proximity_calibration.wave.$key" })
        }
        for ((from, to) in listOf(
            "      pattern: double" to "      pattern: double\n      unknown: true",
            "      near_raw: 0.0" to "      near_raw: '0'",
            "      clear_raw: 1.0" to "      clear_raw: 1e100",
            "format_version: 2" to "format_version: '2'",
        )) {
            assertTrue(yaml.contains(from))
            assertNull(ProfileYaml.parse(yaml.replace(from, to)).document)
        }
    }


    @Test fun `ranged wave direction must agree with presence for either pattern`() {
        for (pattern in listOf("single", "double")) {
            val bad = binary.copy(formatVersion = 2, mode = "ranged", clearRaw = 100f, nearRaw = 50f,
                wave = ProfileWaveCalibration(pattern, 20f, 80f))
            assertTrue(issues(bad).any { it.path.endsWith(".wave") })
        }
    }


    @Test fun `wave only profiles need version two and an independently verified wave capability`() {
        val waveOnly = binary.copy(formatVersion = 2, presenceSupported = false,
            wave = ProfileWaveCalibration("single", 1f, 0f))
        val yaml = ProfileYaml.serialize(document(waveOnly))
        assertTrue(yaml.contains("presence_supported: false"))
        assertEquals(waveOnly, ProfileYaml.parse(yaml).document!!.sensors.proximityCalibration)
        assertEquals(emptyList<ProfileIssue>(), issues(waveOnly))
        assertFalse(ProfileYaml.serialize(document(binary)).contains("presence_supported"))
        assertTrue(ProfileYaml.parse(ProfileYaml.serialize(document(binary))).document!!.sensors.proximityCalibration!!.presenceSupported)
        assertTrue(issues(waveOnly.copy(wave = null)).any { it.path.endsWith(".presence_supported") })
        assertTrue(issues(waveOnly.copy(formatVersion = 1)).any { it.path.endsWith(".presence_supported") })
        assertNull(ProfileYaml.parse(yaml.replace("presence_supported: false", "presence_supported: 'false'")).document)
    }

}
