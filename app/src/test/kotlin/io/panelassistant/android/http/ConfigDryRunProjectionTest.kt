package io.panelassistant.android.http

import io.panelassistant.android.config.ConfigDiff
import io.panelassistant.android.config.ConfigHash
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDryRunProjectionTest {
    @Test fun secretChangesRetainOnlyTheChangedKeySignal() {
        val currentPassword = "stored-password-marker"
        val proposedPassword = "proposed-password-marker"
        val body = configDryRunJson(
            diff = listOf(
                ConfigDiff.Change("mqtt_password", currentPassword, proposedPassword),
                ConfigDiff.Change("ha_token", null, "proposed-token-marker"),
            ),
            skipped = emptyList(),
            warnings = emptyList(),
            expectedConfig = "1234abcd",
        )

        assertFalse(body.contains(currentPassword))
        assertFalse(body.contains(proposedPassword))
        assertFalse(body.contains("proposed-token-marker"))
        val changes = JSONObject(body).getJSONArray("changes")
        assertEquals("[redacted]", changes.getJSONObject(0).getString("from"))
        assertEquals("[redacted]", changes.getJSONObject(0).getString("to"))
        assertEquals("[redacted]", changes.getJSONObject(1).getString("from"))
        assertEquals("[redacted]", changes.getJSONObject(1).getString("to"))
    }

    @Test fun equalAndUnequalSecretGuessesProduceTheSameProjection() {
        val current = mapOf("mqtt_password" to "stored", "friendly_name" to "Panel Alpha")
        val equal = configPreviewDiff(current, mapOf("mqtt_password" to "stored"))
        val unequal = configPreviewDiff(current, mapOf("mqtt_password" to "guess"))

        assertEquals(equal, unequal)
        assertEquals(1, equal.size)
        assertEquals("mqtt_password", equal.single().key)
        val equalJson = configDryRunJson(equal, emptyList(), emptyList(), "1234abcd")
        val unequalJson = configDryRunJson(unequal, emptyList(), emptyList(), "1234abcd")
        assertEquals(equalJson, unequalJson)
        assertFalse(equalJson.contains("stored"))
        assertFalse(unequalJson.contains("guess"))
    }

    @Test fun concurrencyProjectionIgnoresSecretsButTracksOrdinarySettings() {
        val baseline = mapOf(
            "mqtt_password" to "one",
            "ha_token" to "token-one",
            "friendly_name" to "Panel Alpha",
        )
        val secretChange = baseline + ("mqtt_password" to "two") + ("ha_token" to "token-two")
        val ordinaryChange = baseline + ("friendly_name" to "Panel Beta")

        assertEquals(configConcurrencyValues(baseline), configConcurrencyValues(secretChange))
        assertEquals(
            ConfigHash.of(configConcurrencyValues(baseline)),
            ConfigHash.of(configConcurrencyValues(secretChange)),
        )
        assertTrue(configConcurrencyValues(baseline) != configConcurrencyValues(ordinaryChange))
        assertFalse(configConcurrencyValues(baseline).containsKey("mqtt_password"))
        assertFalse(configConcurrencyValues(baseline).containsKey("ha_token"))
    }

    @Test fun ordinaryChangesRemainUsefulAndMetadataIsEscaped() {
        val body = configDryRunJson(
            diff = listOf(ConfigDiff.Change("friendly_name", "Panel Alpha", "Panel Beta")),
            skipped = listOf("device-only"),
            warnings = listOf("quoted \"warning\""),
            expectedConfig = "1234abcd",
        )

        val json = JSONObject(body)
        val change = json.getJSONArray("changes").getJSONObject(0)
        assertEquals("friendly_name", change.getString("key"))
        assertEquals("Panel Alpha", change.getString("from"))
        assertEquals("Panel Beta", change.getString("to"))
        assertEquals("device-only", json.getJSONArray("skipped").getString(0))
        assertEquals("quoted \"warning\"", json.getJSONArray("warnings").getString(0))
    }
}
