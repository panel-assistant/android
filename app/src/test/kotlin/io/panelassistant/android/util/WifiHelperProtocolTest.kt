package io.panelassistant.android.util

import io.panelassistant.android.control.WifiDiagnosticCache
import io.panelassistant.android.control.WifiDiagnosticDemand
import io.panelassistant.android.control.WifiDiagnosticSnapshot
import io.panelassistant.android.platform.HelperWifiStatus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class WifiHelperProtocolTest {
    @Test fun wifiReadAdmitsItsCarryingConnectionAndRefusesAReplacementOrOldHelper() {
        val wire = ZigbeeReadFixture().apply {
            version = "HELPER version=1.9.0 proto=1.9"
            replies["WIFISTATUS"] = "SSID=${"Home, β".zigbeeHex()} RSSI=-63\n".toByteArray()
        }
        wire.afterVersion = { wire.version = "HELPER version=2.0.0 proto=2.9" }
        assertEquals(HelperWifiStatus("Home, β", -63), wire.client.wifiStatus())
        assertNull(wire.client.wifiStatus())
        assertEquals(listOf(Triple(1, "WIFISTATUS", 80L)), wire.bytes)
        assertEquals(listOf(1 to "VERSION", 2 to "VERSION"), wire.bootstraps)
        assertEquals(2, wire.closed)
        assertTrue(wire.rawCommands.isEmpty())
        for (version in listOf("ERR", "HELPER version=1.8.0 proto=1.8", "HELPER version=1.9.0 proto=1.9 proto=1.9")) {
            wire.version = version
            wire.afterVersion = null
            wire.bytes.clear()
            assertNull(wire.client.wifiStatus())
            assertTrue(wire.bytes.isEmpty())
        }
    }

    @Test fun parserRequiresExactFieldsCanonicalRssiStrictUtf8AndWireBounds() {
        val wire = ZigbeeReadFixture().apply { version = "HELPER version=1.9.0 proto=1.9" }
        fun read(raw: String?): HelperWifiStatus? {
            wire.replies["WIFISTATUS"] = raw?.toByteArray()
            return wire.client.wifiStatus()
        }
        for (invalid in listOf(null, "ERR\n", "SSID=61 RSSI=-60", "SSID=61 RSSI=-60\nextra\n", "RSSI=-60 SSID=61\n",
            "SSID= RSSI=-60\n", "SSID=f RSSI=-60\n", "SSID=FF RSSI=-60\n", "SSID=c080 RSSI=-60\n", "SSID=00 RSSI=-60\n",
            "SSID=${"a".repeat(33).zigbeeHex()} RSSI=-60\n", "SSID=61 RSSI=-127\n", "SSID=61 RSSI=1\n",
            "SSID=61 RSSI=-0\n", "SSID=61 RSSI=-01\n", "SSID=61 RSSI=+0\n", "SSID=61 RSSI=-60 extra\n")) {
            assertNull(read(invalid), invalid)
        }
        assertEquals(HelperWifiStatus("β".repeat(16), -126), read("SSID=${"β".repeat(16).zigbeeHex()} RSSI=-126\n"))
        assertEquals(HelperWifiStatus(null, 0), read("SSID=- RSSI=0\n"))
        assertEquals(HelperWifiStatus("a", null), read("SSID=61 RSSI=-\n"))
        assertEquals(HelperWifiStatus(null, null), read("SSID=- RSSI=-\n"))
    }

    @Test fun separateMissingSsidAndRssiDemandsShareOneTypedHelperRead() {
        val wire = ZigbeeReadFixture().apply {
            version = "HELPER version=1.9.0 proto=1.9"
            replies["WIFISTATUS"] = "SSID=61 RSSI=-60\n".toByteArray()
        }
        val cache = WifiDiagnosticCache { 1_000L }
        fun sample(demand: WifiDiagnosticDemand) = cache.snapshot(demand, { WifiDiagnosticSnapshot(active = true) }, {
            wire.client.wifiStatus()?.let { WifiDiagnosticSnapshot(it.ssid, it.rssiDbm) } ?: WifiDiagnosticSnapshot()
        })
        assertEquals(WifiDiagnosticSnapshot(ssid = "a", active = true), sample(WifiDiagnosticDemand(ssid = true)))
        assertEquals(WifiDiagnosticSnapshot(rssiDbm = -60, active = true), sample(WifiDiagnosticDemand(rssi = true)))
        assertEquals(listOf(Triple(1, "WIFISTATUS", 80L)), wire.bytes)
    }

    @Test fun repeatedMissingOldRefusedAndMalformedHelperNeverReusesExpiredDataOrUsesRawCommands() {
        for (mode in listOf("missing", "old", "refused", "malformed")) {
            var now = 1000L
            val wire = ZigbeeReadFixture().apply {
                version = "HELPER version=1.9.0 proto=1.9"
                replies["WIFISTATUS"] = "SSID=61 RSSI=-50\n".toByteArray()
            }
            val cache = WifiDiagnosticCache { now }
            fun sample() = cache.snapshot(WifiDiagnosticDemand(true, true), { WifiDiagnosticSnapshot(active = true) }, {
                wire.client.wifiStatus()?.let { WifiDiagnosticSnapshot(it.ssid, it.rssiDbm) } ?: WifiDiagnosticSnapshot()
            })
            assertEquals("a", sample().ssid)
            when (mode) {
                "missing" -> wire.missing = true
                "old" -> wire.version = "HELPER version=1.8.0 proto=1.8"
                "refused" -> wire.replies["WIFISTATUS"] = "ERR\n".toByteArray()
                else -> wire.replies["WIFISTATUS"] = "SSID=ff RSSI=-50\n".toByteArray()
            }
            repeat(3) { now += 15_000L; assertEquals(WifiDiagnosticSnapshot(active = true), sample(), mode) }
            assertTrue(wire.rawCommands.isEmpty())
        }
    }
}
