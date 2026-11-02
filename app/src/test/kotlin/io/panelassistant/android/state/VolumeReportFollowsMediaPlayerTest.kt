package io.panelassistant.android.state

import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Panel Assistant's media player shows and sets the volume through the volume channel, so on a panel with
 * a speaker the volume is reported whatever its old exposure preference says.
 */
internal class VolumeReportFollowsMediaPlayerTest : PanelRuntimeRig() {
    @Test fun aPanelWithASpeakerReportsItsVolumeEvenWhenUnexposed() {
        val rig = rig(configure = { it.setHaExposed("volume", false) })
        try {
            rig.audio.percent = 37
            val native = PanelAssistantStateReporter(log = {})
            rig.core.addStateSink(native.bindShape(rig.core::nativeChannelShape))
            rig.core.reconcile("volume")
            native.open(native.offer().descriptors)
            val report = native.next(1, "volume-session", 0L)
                ?: throw AssertionError("the native session did not report its initial state")
            val observations = JSONObject(report).getJSONArray("observations")
            val volume = (0 until observations.length()).map(observations::getJSONObject)
                .singleOrNull { it.getString("channel") == "volume" }
                ?: throw AssertionError("the native session omitted volume: $report")
            assertEquals("known", volume.getString("state"))
            assertEquals(37, volume.getInt("value"))
        } finally { rig.close() }
    }

}
