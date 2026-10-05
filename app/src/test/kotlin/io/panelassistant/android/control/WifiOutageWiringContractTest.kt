package io.panelassistant.android.control

import io.panelassistant.android.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOutageWiringContractTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    private val bridge = source("MqttBridge.kt")

    @Test fun outageCountersStayRetainedThroughTheDropoutTheyReport() {
        // WIFI_DIAGNOSTIC_KEYS members tombstone their retained state when Wi-Fi goes away — the
        // opposite of what a dropout counter wants. The counters must stay out of that set.
        // Bounded at the set literal's closing paren, not the first newline, so the guard keeps
        // scanning the whole declaration if it is ever reformatted across lines.
        val keys = bridge.substring(
            bridge.indexOf("private val WIFI_DIAGNOSTIC_KEYS"),
            bridge.indexOf(")", bridge.indexOf("private val WIFI_DIAGNOSTIC_KEYS")),
        )
        assertFalse(keys.contains("diag_wifi_outages_24h"))
        assertFalse(keys.contains("diag_wifi_outages_7d"))

        // Exact-count convergence: no deadband entry, so every change of an integer count publishes.
        val deadband = bridge.substring(
            bridge.indexOf("val diagDeadband"),
            bridge.indexOf(")", bridge.indexOf("val diagDeadband")),
        )
        assertFalse(deadband.contains("diag_wifi_outages"))
    }

    @Test fun theCounterReadsTheTrackerSnapshot() {
        assertTrue(bridge.contains(""""diag_wifi_outages_24h" -> wifiOutages()?.last24h?.toString()"""))
        // Membership in DIAG_KEYS is what wires discovery and the heartbeat refresh; a diagValue
        // branch alone is unreachable code.
        val diagKeys = bridge.substring(
            bridge.indexOf("private val DIAG_KEYS"),
            bridge.indexOf(")", bridge.indexOf("private val DIAG_KEYS")),
        )
        assertTrue(diagKeys.contains("\"diag_wifi_outages_24h\""))
    }

    @Test fun theSensorPublishesWhetherItsNumberIsAFloor() {
        // An integer state cannot say "at least"; without the attribute Home Assistant records a
        // capped 200 as an exact measurement while the panel's own row says it is a floor.
        assertTrue(bridge.contains("""json_attributes_topic":"ha-paneld/{panel}/diag_wifi_outages_24h/attributes""") ||
            SettingsRegistry.spec("diag_wifi_outages_24h")!!.ha!!.body
                .contains("""json_attributes_topic":"ha-paneld/{panel}/diag_wifi_outages_24h/attributes"""))
        assertTrue(bridge.contains("""channel("diag_wifi_outages_attributes", attrWifiOutages)"""))
        assertTrue(bridge.contains("""put("is_lower_bound", counts.saturated)"""))
    }

    @Test fun theRetiredWeeklySensorCannotLeaveAGhost() {
        // Retiring an entity needs BOTH halves: its config in the historical superset and its
        // retained state topic in cleanup, plus a discovery-shape bump so a same-version upgrade
        // actually republishes and clears it.
        val dollar = "$"
        assertTrue(bridge.contains(""""sensor" to "${dollar}{panel}_diag_wifi_outages_7d""""))
        assertTrue(bridge.contains(""""ha-paneld/${dollar}panel/diag_wifi_outages_7d/state""""))
        val revision = Regex("""MQTT_DISCOVERY_SHAPE_REVISION = (\d+)""").find(bridge)?.groupValues?.get(1)
        assertTrue("the discovery shape revision must have advanced past 4", (revision?.toIntOrNull() ?: 0) >= 5)
    }

    @Test fun legacyXmlMirrorIsExcludedFromAndroidBackup() {
        // StateBackupPolicy's DEVICE_LOCAL gates only the app's own sealed restore flow. Android
        // backup and device-to-device transfer need their own exclusion, or the record migrates to
        // a different panel and reports the source home's outages as this one's.
        val working = File(requireNotNull(System.getProperty("user.dir")))
        // Source-text reason: the shipped backup-rules XML is the Android backup contract, read as data.
        for ((rules, sections) in listOf("backup_rules.xml" to 1, "data_extraction_rules.xml" to 2)) {
            val text = listOf(
                File(working, "app/src/main/res/xml/$rules"),
                File(working, "src/main/res/xml/$rules"),
            ).first(File::isFile).readText()
            val occurrences = Regex(Regex.escape("""path="ha-paneld-wifi-stability.xml"""")).findAll(text).count()
            assertTrue(
                "$rules must exclude the wifi-stability legacy mirror in every section",
                occurrences >= sections,
            )
        }
    }

    private fun source(relative: String): String {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = if (relative.contains('/')) {
            listOf(relative)
        } else {
            listOf(relative, "control/$relative")
        }.flatMap {
            listOf(
                File(working, "app/src/main/kotlin/io/panelassistant/android/$it"),
                File(working, "src/main/kotlin/io/panelassistant/android/$it"),
            )
        }
        return candidates.first(File::isFile).readText()
    }
}
