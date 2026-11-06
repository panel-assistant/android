package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDiagnosticsTest {
    @Test fun helperFallbackRequiresDemandedMissingDirectDataOnActiveWifi() {
        val rssiOnly = WifiDiagnosticDemand(rssi = true)
        assertEquals(
            false,
            needsHelperWifiStatus(WifiDiagnosticSnapshot(ssid = null, rssiDbm = -61, active = true), rssiOnly),
        )
        assertEquals(
            true,
            needsHelperWifiStatus(WifiDiagnosticSnapshot(ssid = "hidden", rssiDbm = null, active = true), rssiOnly),
        )
        assertEquals(
            false,
            needsHelperWifiStatus(
                WifiDiagnosticSnapshot(),
                WifiDiagnosticDemand(rssi = true),
            ),
        )
        assertEquals(
            false,
            needsHelperWifiStatus(
                WifiDiagnosticSnapshot(),
                WifiDiagnosticDemand(ssid = false, rssi = false),
            ),
        )
        assertEquals(
            false,
            needsHelperWifiStatus(
                WifiDiagnosticSnapshot(ssid = "stale", rssiDbm = -55, active = false),
                WifiDiagnosticDemand(ssid = true, rssi = true),
            ),
        )
    }

    @Test fun ssidNormalizationRemovesFrameworkQuotesAndRejectsRedaction() {
        assertEquals("Example Wi-Fi", normalizedWifiSsid("\"Example Wi-Fi\""))
        assertEquals("Example Wi-Fi", normalizedWifiSsid(" Example Wi-Fi "))
        assertNull(normalizedWifiSsid("<unknown ssid>"))
        assertNull(normalizedWifiSsid("unknown SSID"))
        assertNull(normalizedWifiSsid("  "))
        assertNull(normalizedWifiSsid(null))
    }

    @Test fun rssiNormalizationKeepsRealDbmAndRejectsFrameworkSentinels() {
        assertEquals(-63, normalizedWifiRssi(-63))
        assertEquals(0, normalizedWifiRssi(0))
        assertNull(normalizedWifiRssi(-127))
        assertNull(normalizedWifiRssi(1))
    }

    @Test fun ethernetRejectsStaleApi27WifiInfoWhileActiveWifiAdmitsSaneValues() {
        assertEquals(
            WifiDiagnosticSnapshot(),
            observedWifiSnapshot(activeWifi = false, rawSsid = "Old network", rawRssiDbm = -58),
        )
        assertEquals(
            WifiDiagnosticSnapshot("Current network", -67, active = true),
            observedWifiSnapshot(activeWifi = true, rawSsid = "\"Current network\"", rawRssiDbm = -67),
        )
        assertEquals(
            WifiDiagnosticAvailability(ssid = false, rssi = false),
            observedWifiSnapshot(false, "Old network", -58).availability(),
        )
        assertEquals(
            WifiDiagnosticAvailability(ssid = true, rssi = true),
            observedWifiSnapshot(true, "Current network", -67).availability(),
        )
    }

    @Test fun diagnosticAdmissionTracksSameTransportAvailabilityChanges() {
        val tracker = WifiDiagnosticAdmissionTracker()
        val ethernet = WifiDiagnosticAdmission(active = false, ssid = false, rssi = false)
        val redactedWifi = WifiDiagnosticAdmission(active = true, ssid = false, rssi = false)
        val readableWifi = WifiDiagnosticAdmission(active = true, ssid = true, rssi = true)
        assertTrue(tracker.changed(ethernet))
        assertFalse(tracker.changed(ethernet))
        assertTrue(tracker.changed(redactedWifi))
        assertFalse(tracker.changed(redactedWifi))
        assertTrue(tracker.changed(readableWifi))
        assertFalse(tracker.changed(readableWifi))
        assertTrue(tracker.changed(redactedWifi))
        assertTrue(tracker.changed(ethernet))
    }

    @Test fun invalidationImmediatelyReReadsDirectAndPrivilegedWifiState() {
        var direct = WifiDiagnosticSnapshot(active = true)
        var privileged = WifiDiagnosticSnapshot("Network A", -60)
        var directReads = 0
        var privilegedReads = 0
        val cache = WifiDiagnosticCache(nowMs = { 1_000L })
        val demand = WifiDiagnosticDemand(ssid = true, rssi = true)
        fun read() = cache.snapshot(
            demand,
            directReader = { directReads++; direct },
            helperReader = { privilegedReads++; privileged },
        )

        assertEquals(WifiDiagnosticSnapshot("Network A", -60, active = true), read())
        direct = WifiDiagnosticSnapshot("Network B", -75, active = true)
        privileged = WifiDiagnosticSnapshot("stale root value", -50)
        assertEquals(WifiDiagnosticSnapshot("Network A", -60, active = true), read())
        assertEquals(1, directReads)
        assertEquals(1, privilegedReads)

        cache.invalidate()
        assertEquals(WifiDiagnosticSnapshot("Network B", -75, active = true), read())
        assertEquals(2, directReads)
        assertEquals(1, privilegedReads)
    }

    @Test fun ethernetNeverInvokesPrivilegedWifiFallback() {
        var privilegedReads = 0
        val cache = WifiDiagnosticCache(nowMs = { 1_000L })
        val result = cache.snapshot(
            WifiDiagnosticDemand(ssid = true, rssi = true),
            directReader = { WifiDiagnosticSnapshot(active = false) },
            helperReader = { privilegedReads++; WifiDiagnosticSnapshot("stale", -50) },
        )

        assertEquals(WifiDiagnosticSnapshot(), result)
        assertEquals(0, privilegedReads)
    }

}
