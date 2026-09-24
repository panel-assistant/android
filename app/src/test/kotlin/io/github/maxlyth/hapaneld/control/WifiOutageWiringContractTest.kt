package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outage tracker's wiring is Context/service-bound with no Robolectric in the unit gate, so —
 * matching the repo idiom (AutoBrightnessComputeBudgetContractTest) — these are source-contract
 * invariants, not live behaviour tests. Each pins a decision the tracker's own unit tests cannot
 * see: which real signals feed it, and that the Wi-Fi-vs-somebody-else's-outage boundary holds.
 */
class WifiOutageWiringContractTest {
    private val service = source("PaneldService.kt")
    private val bridge = source("MqttBridge.kt")

    @Test fun everyTrackerSignalComesFromTheGuardedDefaultNetworkCallback() {
        val callback = service.substring(
            service.indexOf("private fun registerNetworkCallback()"),
            service.indexOf("runCatching { cm.registerDefaultNetworkCallback"),
        )

        // Loss is read strictly behind the network-identity guard, so a make-before-break handover
        // (whose stale onLost names a network that is no longer the default) never opens an episode.
        val lost = callback.substring(callback.indexOf("override fun onLost"))
        assertTrue(lost.contains("wifiOutageTracker.onDefaultLost()"))
        assertTrue(
            "onDefaultLost must sit behind the identity guard",
            lost.indexOf("if (network != defaultNetwork) return") <
                lost.indexOf("wifiOutageTracker.onDefaultLost()"),
        )

        // Recovery reports IDENTITY ONLY. The synchronous capability snapshot taken during
        // onAvailable can predate the authoritative callback, so the tracker must never be handed a
        // transport from it — the behavioural consequence is pinned by the tracker's own tests.
        val available = callback.substring(
            callback.indexOf("override fun onAvailable"),
            callback.indexOf("override fun onLinkPropertiesChanged"),
        )
        val trackerArrival = available.substring(
            available.indexOf("wifiOutageTracker.onDefaultAvailable("),
            available.indexOf(")", available.indexOf("wifiOutageTracker.onDefaultAvailable(")) + 1,
        )
        assertTrue("the tracker is told which network arrived", trackerArrival.contains("network.hashCode()"))
        assertFalse(
            "no transport may be read from the synchronous snapshot at arrival",
            trackerArrival.contains("hasTransport"),
        )

        // Transport updates apply only to the current default network — a background network's
        // capability churn must not relabel the default as Wi-Fi or not-Wi-Fi.
        val capabilitiesChanged = callback.substring(
            callback.indexOf("override fun onCapabilitiesChanged"),
            callback.indexOf("override fun onLost"),
        )
        assertTrue(capabilitiesChanged.contains("if (network == defaultNetwork)"))
        assertTrue(
            "onTransportChanged must be gated on the default-network identity",
            capabilitiesChanged.indexOf("if (network == defaultNetwork)") <
                capabilitiesChanged.indexOf("wifiOutageTracker.onTransportChanged("),
        )

        // The whole feature observes ConnectivityManager only: no tracker call may be reachable
        // from MQTT or Home Assistant connection state, whose outages are not the network's fault.
        val trackerCalls = Regex("""wifiOutageTracker\.on\w+\(""").findAll(service).count()
        val callbackCalls = Regex("""wifiOutageTracker\.on\w+\(""").findAll(callback).count()
        assertTrue("every tracker event call lives inside registerNetworkCallback", trackerCalls == callbackCalls)
    }

    @Test fun runtimeDiagnosticsRowSuppressesACleanPanel() {
        // The row value is null while the last 24 hours are clean; it must vanish, not show zero.
        assertTrue(service.contains("""wifiOutageStatusText(wifiOutages)?.let { extras["Wi-Fi stability"] = it }"""))
    }

    /**
     * The diagnostics row and the `/diag` gate answer different questions about the SAME moment, so
     * they must be derived from one read. Two reads could straddle an episode ageing out of the
     * window and produce a report that omits the line while printing a row saying the Wi-Fi needs
     * attention — a self-contradicting bug report, which is worse than either answer alone.
     */
    @Test fun theRowAndTheDiagGateAreDerivedFromOneOutageRead() {
        val projection = service.substring(
            service.indexOf("private fun managementProjection("),
            service.indexOf("/** Ordered facts for the info page"),
        )
        assertTrue(projection.contains("val wifi = wifiOutageTracker.counts()"))
        assertTrue(projection.contains("facts = panelInfo(controllers, diagnostic.rgbLedReady, wifi)"))
        assertTrue(projection.contains("wifiChronic = wifiOutageChronic(wifi)"))
        assertEquals(
            "the projection must read the tracker exactly once",
            1,
            Regex("""wifiOutageTracker\.counts\(\)""").findAll(projection).count(),
        )
    }

    /**
     * `/diag` is pasted into bug reports and is terse by design, so the Wi-Fi line belongs there only
     * once the instability is chronic — and the decision it gates on must be the projection's, not a
     * second opinion formed later inside the report.
     */
    @Test fun theDiagReportGatesTheWifiLineOnThatSameDecision() {
        val server = source("http/PaneldServer.kt")
        assertTrue(server.contains("wifiChronic = management.wifiChronic"))
        assertTrue(server.contains("wifiStabilityChronic = management.wifiChronic"))
        // Every CALL (the declaration is excluded by the lookbehind) must pass the decision, so no
        // second site can reinstate the line unconditionally by relying on the omit-by-default arg.
        val reader = source("http/DiagReader.kt")
        assertEquals(
            listOf("facts, wifiStabilityChronic"),
            Regex("""(?<!fun )publicPanelFacts\(([^)]*)\)""").findAll(reader).map { it.groupValues[1] }.toList(),
        )
    }

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

    @Test fun wifiStabilityIsARuntimeDiagnosticsFact() {
        val server = source("http/PaneldServer.kt")
        val contextKeys = Regex("""private val CONTEXT_KEYS = listOf\(([^)]*)\)""")
            .find(server)?.groupValues?.get(1).orEmpty()
        assertTrue("Wi-Fi stability must be a Runtime diagnostics row", contextKeys.contains(""""Wi-Fi stability""""))
        // `contextRowsHtml` walks CONTEXT_KEYS in order and drops absent keys, so heading the list is
        // exactly "position 1 whenever the row is shown at all". Pinned because the row's whole value is
        // being noticed: buried under seven healthy rows it is a footnote to the problem it explains.
        assertTrue(
            "Wi-Fi stability must LEAD the Runtime diagnostics card, not sit mid-list",
            contextKeys.trim().startsWith(""""Wi-Fi stability""""),
        )
        val behaviourKeys = Regex("""private val BEHAVIOUR_FACT_KEYS = setOf\(([^)]*)\)""")
            .find(server)?.groupValues?.get(1).orEmpty()
        assertFalse(behaviourKeys.contains(""""Wi-Fi stability""""))
    }

    @Test fun storeKeepsTheClassifiedNamespaceAndNoNetworkIdentity() {
        val store = source("control/AndroidWifiOutageStore.kt")
        assertTrue(store.contains("""namespace = "wifi-stability""""))
        // The persisted record is episode instants only — never SSID, BSSID or MAC.
        val trackerSource = source("control/WifiOutageTracker.kt")
        for (identity in listOf("ssid", "SSID", "bssid", "BSSID", "MacAddress")) {
            assertFalse(
                "the outage record must not carry network identity ($identity)",
                trackerSource.contains("\"$identity"),
            )
        }
    }

    @Test fun legacyXmlMirrorIsExcludedFromAndroidBackup() {
        // StateBackupPolicy's DEVICE_LOCAL gates only the app's own sealed restore flow. Android
        // backup and device-to-device transfer need their own exclusion, or the record migrates to
        // a different panel and reports the source home's outages as this one's.
        val working = File(requireNotNull(System.getProperty("user.dir")))
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
                File(working, "app/src/main/kotlin/io/github/maxlyth/hapaneld/$it"),
                File(working, "src/main/kotlin/io/github/maxlyth/hapaneld/$it"),
            )
        }
        return candidates.first(File::isFile).readText()
    }
}
