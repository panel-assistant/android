package io.panelassistant.android.http

import io.panelassistant.android.config.Migrations
import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup taken before the voice assistant's MQTT switch and state sensor were removed still carries
 * their exposure flags, at the same schema. Restore is all or nothing, so those two stale keys must not
 * cost the owner the backup they took just before updating.
 */
class RestorePlanRetiredVoiceKeysTest {

    @Test fun `a backup from before the voice entities were removed still restores`() {
        val archived = mapOf(
            "panel_id" to "alpha",
            "voice_enabled" to "true",
            "ha_expose_voice_enabled" to "true",
            "ha_expose_voice_state" to "false",
            "ha_expose_volume" to "true",
        )
        val (migrated, _) = Migrations.migrate(SettingsRegistry.SCHEMA, archived)
        val decision = planRestoreSettings(migrated, null)

        assertTrue("backup must restore, errors were ${decision.errors}", decision.errors.isEmpty())
        assertEquals("alpha", decision.accepted["panel_id"])
        assertEquals("true", decision.accepted["voice_enabled"])
        assertEquals("true", decision.accepted["ha_expose_volume"])
        assertFalse(decision.accepted.containsKey("ha_expose_voice_enabled"))
        assertFalse(decision.accepted.containsKey("ha_expose_voice_state"))
    }
}
