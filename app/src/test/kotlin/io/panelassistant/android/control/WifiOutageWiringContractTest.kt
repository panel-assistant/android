package io.panelassistant.android.control

import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WifiOutageWiringContractTest : PanelRuntimeRig() {
    @Test fun outageCounterReportsItsSnapshotWhenWifiHasNoReading() {
        val rig = rig(wifiOutages = { WifiOutageCounts(last24h = 17) })
        try {
            rig.config.setHaExposed("diag_wifi_rssi", true)
            val reporter = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(reporter.bindShape(rig.core::nativeChannelShape))
            assertTrue(runBlocking { rig.core.observeForNativeHello { true } })
            reporter.open(reporter.offer().descriptors, true)
            val raw = reporter.next(1, "outage-session", 0)
            assertNotNull("the state owner emitted a native report", raw)
            val observations = JSONObject(raw!!).getJSONArray("observations")
            fun observation(channel: String): JSONObject? =
                (0 until observations.length()).map(observations::getJSONObject)
                    .singleOrNull { it.optString("channel") == channel }

            val outage = observation("diag_wifi_outages_24h")
            assertNotNull("the outage channel is registered and reported", outage)
            assertEquals("known", outage!!.optString("state"))
            assertEquals(17, outage.optInt("value", -1))
            val rssi = observation("diag_wifi_rssi")
            assertNotNull("the exposed Wi-Fi channel is reported", rssi)
            assertEquals("unknown", rssi!!.optString("state"))
            assertEquals("not_on_wifi", rssi.optString("reason"))
        } finally {
            rig.close()
        }
    }
}
