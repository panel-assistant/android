package io.panelassistant.android.mqtt

import io.panelassistant.android.MqttBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `default_entity_id` discovery-payload injection — the field HA actually honours to pin an MQTT
 * entity_id to the stable panel_id, independent of the (renameable) device/friendly name. HA ignores the
 * legacy `object_id` key; MQTT entities are always `has_entity_name=True`, so without this the entity_id
 * would derive from `slug(device.name + entity.name)` and drift on a friendly-name rename. Verified
 * against live HA 2026.7.1 (source + entity registry): friendly names like "Beta HA Panel" coexist with
 * `light.beta_ha_paneld_screen` entity_ids precisely because of this field.
 */
class DefaultEntityIdTest {

    @Test fun injectsDefaultEntityIdAsFirstKey() {
        val out = MqttBridge.withDefaultEntityId("light", "alpha_ha_paneld_screen", """{"name":"Screen","unique_id":"alpha_ha_paneld_screen"}""")
        assertTrue(
            "must prepend default_entity_id=<component>.<objectId>, got: $out",
            out.startsWith("""{"default_entity_id":"light.alpha_ha_paneld_screen","name":"Screen"""),
        )
        // Original keys are preserved intact.
        assertTrue(out.contains(""""unique_id":"alpha_ha_paneld_screen""""))
    }

    /** The whole point: the entity_id base is the panel_id-derived objectId, NOT any device/friendly name
     *  that happens to be in the payload — so renaming the friendly name never drifts the id. */
    @Test fun entityIdIsAnchoredToObjectIdNotDeviceName() {
        val payload = """{"name":"Screen","device":{"name":"Beta HA Panel"}}"""
        val out = MqttBridge.withDefaultEntityId("light", "beta_ha_paneld_screen", payload)
        assertTrue("entity_id must be pinned to the panel_id objectId", out.contains(""""default_entity_id":"light.beta_ha_paneld_screen""""))
        // The mutable friendly name is left untouched in device.name (pretty display) but does not drive the id.
        assertTrue(out.contains(""""name":"Beta HA Panel""""))
    }

    @Test fun emptyPayloadIsUnchangedTombstone() {
        assertEquals("", MqttBridge.withDefaultEntityId("light", "x_screen", ""))
    }

    @Test fun idempotentWhenAlreadyPresent() {
        val already = """{"default_entity_id":"light.x_screen","name":"Screen"}"""
        assertEquals(already, MqttBridge.withDefaultEntityId("light", "x_screen", already))
        // And no double-injection.
        assertFalse(MqttBridge.withDefaultEntityId("light", "x_screen", already).removePrefix("""{"default_entity_id":"light.x_screen",""").contains("default_entity_id"))
    }

    @Test fun componentAndObjectIdComposeTheEntityId() {
        assertTrue(MqttBridge.withDefaultEntityId("sensor", "alpha_ha_paneld_illuminance", """{"x":1}""")
            .startsWith("""{"default_entity_id":"sensor.alpha_ha_paneld_illuminance","""))
        assertTrue(MqttBridge.withDefaultEntityId("binary_sensor", "alpha_ha_paneld_proximity", """{"x":1}""")
            .startsWith("""{"default_entity_id":"binary_sensor.alpha_ha_paneld_proximity","""))
    }
}
