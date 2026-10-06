package io.panelassistant.android.mqtt

import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry side of the illuminance gate. Whether the panel describes the channel, and the live
 * re-announce when that answer changes, are pinned through the production bridge in
 * [NativeLightChannelTest].
 */
class IlluminanceAvailabilityContractTest {
    @Test fun `an unavailable light sensor withdraws the illuminance entity and its retained state`() {
        val spec = SettingsRegistry.spec("illuminance")!!
        assertFalse(spec.availableWhen(Capabilities(hasLight = false)))
        assertTrue(spec.availableWhen(Capabilities(hasLight = true)))
        // Withdrawal clears the retained state topic, so Home Assistant stops recording empty states.
        assertEquals(
            "ha-paneld/test/illuminance/state",
            io.panelassistant.android.hiddenReadOnlyStateTopic("illuminance", "test"),
        )
    }
}
