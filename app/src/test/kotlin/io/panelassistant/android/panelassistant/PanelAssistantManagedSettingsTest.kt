package io.panelassistant.android.panelassistant

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantManagedSettingsTest {
    private val committed = mutableListOf<Map<String, String>>()

    private suspend fun apply(settings: Map<String, String>, valid: Boolean = true, commits: Boolean = true) =
        PanelAssistantManagedSettings.apply(
            settings,
            validate = { values -> if (valid) values.mapValues { it.value.trim() } else null },
            commit = { accepted -> committed += accepted; commits },
        )

    @Test fun `wake words pass the Configure validation and reach the store`() = runTest {
        assertNull(apply(mapOf("voice_wake_words" to " [\"hey_jarvis\"] ")))
        assertEquals(listOf(mapOf("voice_wake_words" to "[\"hey_jarvis\"]")), committed)
    }

    @Test fun `a key outside the list is refused before validation or the store`() = runTest {
        // Network ADB is held for approval on the panel over HTTP; the session has no peer to approve.
        assertEquals("not_commandable", apply(mapOf("network_adb" to "true")))
        assertEquals("not_commandable", apply(mapOf("voice_wake_words" to "[]", "network_adb" to "true")))
        assertEquals("not_commandable", apply(emptyMap()))
        assertTrue(committed.isEmpty())
    }

    @Test fun `a refused value and a failed commit are told apart`() = runTest {
        assertEquals("invalid_value", apply(mapOf("voice_wake_words" to "nope"), valid = false))
        assertTrue(committed.isEmpty())
        assertEquals("failed", apply(mapOf("voice_wake_words" to "[]"), commits = false))
    }
}
