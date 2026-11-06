package io.panelassistant.android.control

import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Uses only pre-WIFISTATUS APIs so this file can demonstrate old-base behavioral failure. */
internal class WifiHelperReadBehaviorTest : PanelRuntimeRig() {
    @Test fun demandedMissingFieldsUseHelperWithoutDirectSuOrNetworkAdbAdmission() {
        var reads = 0
        val cache = WifiDiagnosticCache { 1_000L }
        val value = cache.snapshot(
            WifiDiagnosticDemand(ssid = true, rssi = true),
            { WifiDiagnosticSnapshot(active = true) },
            { reads++; WifiDiagnosticSnapshot("Network, β", -61) },
        )
        assertEquals(WifiDiagnosticSnapshot("Network, β", -61, active = true), value)
        assertEquals(1, reads)
    }

    @Test fun directValuesWinAndSeparateDemandsShareOneHelperSnapshot() {
        var reads = 0
        val cache = WifiDiagnosticCache { 1_000L }
        fun read(demand: WifiDiagnosticDemand) = cache.snapshot(
            demand, { WifiDiagnosticSnapshot("Direct network", active = true) },
            { reads++; WifiDiagnosticSnapshot("Helper network", -65) },
        )
        assertEquals(WifiDiagnosticSnapshot("Direct network", active = true), read(WifiDiagnosticDemand(ssid = true)))
        assertEquals(0, reads)
        assertEquals(WifiDiagnosticSnapshot(rssiDbm = -65, active = true), read(WifiDiagnosticDemand(rssi = true)))
        assertEquals(WifiDiagnosticSnapshot("Direct network", -65, active = true), read(WifiDiagnosticDemand(ssid = true, rssi = true)))
        assertEquals(1, reads)
    }

    @Test fun noDemandEthernetAndCompleteDirectFieldsNeverInvokeHelper() {
        for ((direct, demand) in listOf(
            WifiDiagnosticSnapshot(active = true) to WifiDiagnosticDemand(),
            WifiDiagnosticSnapshot() to WifiDiagnosticDemand(true, true),
            WifiDiagnosticSnapshot("Direct", -50, true) to WifiDiagnosticDemand(true, true),
        )) {
            var reads = 0
            val value = WifiDiagnosticCache { 1_000L }.snapshot(demand, { direct }, {
                reads++; WifiDiagnosticSnapshot("Stale", -40, active = true)
            })
            assertEquals(0, reads)
            assertEquals(direct.active, value.active)
            if (!direct.active) assertEquals(WifiDiagnosticSnapshot(), value)
        }
    }

    @Test fun cacheExpiresAtFifteenSecondsAndInvalidationClearsBothSources() {
        var now = 1_000L
        var directReads = 0
        var helperReads = 0
        var helper = WifiDiagnosticSnapshot("First", -60)
        val cache = WifiDiagnosticCache { now }
        fun read() = cache.snapshot(WifiDiagnosticDemand(true, true), {
            directReads++; WifiDiagnosticSnapshot(active = true)
        }, { helperReads++; helper })
        assertEquals("First", read().ssid)
        helper = WifiDiagnosticSnapshot()
        now = 15_999L
        assertEquals("First", read().ssid)
        assertEquals(1, helperReads)
        now = 16_000L
        assertEquals(WifiDiagnosticSnapshot(active = true), read())
        assertEquals(2, helperReads)
        helper = WifiDiagnosticSnapshot("Restored", -70)
        cache.invalidate()
        assertEquals(WifiDiagnosticSnapshot("Restored", -70, true), read())
        assertEquals(3, helperReads)
        assertEquals(3, directReads)
    }

    @Test fun nativeReporterPublishesWifiWithoutNetworkAdbCapability() {
        val rig = rig()
        val cache = WifiDiagnosticCache { 1_000L }
        val reader: (WifiDiagnosticDemand) -> WifiDiagnosticSnapshot = { demand ->
            cache.snapshot(demand, { WifiDiagnosticSnapshot(active = true) }, { WifiDiagnosticSnapshot("Home β", -64) })
        }
        // Use the existing injected diagnostic reader on the real state owner; no test-only production seam.
        rig.core.javaClass.getDeclaredField("wifiDiagnostics").apply { isAccessible = true }.set(rig.core, reader)
        try {
            rig.config.setHaExposed("diag_wifi_ssid", true)
            rig.config.setHaExposed("diag_wifi_rssi", true)
            assertEquals(false, rig.core.capabilityShape.current().live.networkAdb)
            val reporter = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(reporter.bindShape(rig.core::nativeChannelShape))
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            reporter.open(reporter.offer().descriptors, true)
            val observations = JSONObject(requireNotNull(reporter.next(1, "wifi-session", 0))).getJSONArray("observations")
            fun value(key: String) = (0 until observations.length()).map(observations::getJSONObject)
                .single { it.optString("channel") == key }
            assertEquals("known", value("diag_wifi_ssid").optString("state"))
            assertEquals("Home β", value("diag_wifi_ssid").getString("value"))
            assertEquals(-64, value("diag_wifi_rssi").getInt("value"))
        } finally { rig.close() }
    }
}
