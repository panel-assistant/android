package io.panelassistant.android.config

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upgrade-visible semantic pin: `mqtt_address_family` retains its key for compatibility but now
 * governs the MQTT broker connection AND the panel's Home Assistant WebSocket connections. An existing
 * non-default value therefore broadens on upgrade, and the user-visible label/help must say so —
 * this pins the semantic tokens, not sentence wording.
 */
class AddressFamilyScopeSpecTest {
    @Test fun addressFamilySettingNamesBothGovernedConnectionScopes() {
        val spec = checkNotNull(SettingsRegistry.spec("mqtt_address_family"))
        val surface = spec.label + " " + spec.help
        assertTrue("names MQTT: $surface", surface.contains("MQTT"))
        assertTrue("names Home Assistant: $surface", surface.contains("Home Assistant"))
        // The qualifier is part of the pinned semantic: the policy governs WebSocket connections,
        // and claiming plain Home Assistant "connections" overstates it — REST reads iterate
        // addresses natively and do not consult this setting.
        assertTrue("names the WebSocket qualifier: $surface", surface.contains("WebSocket"))
    }
}
