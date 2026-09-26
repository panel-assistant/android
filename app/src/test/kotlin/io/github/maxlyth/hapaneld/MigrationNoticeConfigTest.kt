package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationNoticeConfigTest {
    @Test fun `unmanaged panel keeps its notice across restarts`() {
        val state = Preferences()
        assertTrue(Config(state.preferences).migrationNoticeVisible(100))
        assertTrue(Config(state.preferences).migrationNoticeVisible(100))
    }

    @Test fun `accepted Panel Assistant session clears the notice through the production config writer`() {
        val config = Config(Preferences().preferences)
        assertTrue(config.migrationNoticeVisible(100))
        config.setPanelAssistantAuthority("mqtt")
        assertTrue(config.migrationNoticeVisible(100))
        config.markPanelAssistantConnected()
        assertFalse(config.migrationNoticeVisible(100))
        assertFalse(config.migrationNoticeVisible(101))
    }

    @Test fun `polling integration clears the notice even without a native session`() {
        val state = Preferences()
        val config = Config(state.preferences)
        config.setPanelAssistantUpdateOwnerSeenMs(1234)
        assertFalse(config.migrationNoticeVisible(100))
        assertFalse(Config(state.preferences).migrationNoticeVisible(101))
    }

    @Test fun `dismissal survives restart and notice returns after an update`() {
        val state = Preferences()
        val config = Config(state.preferences)
        assertTrue(config.dismissMigrationNotice(100))
        assertFalse(config.migrationNoticeVisible(100))
        assertFalse(Config(state.preferences).migrationNoticeVisible(100))
        assertTrue(Config(state.preferences).migrationNoticeVisible(101))
        assertFalse(config.panelAssistantConnectionSeen)
    }

    @Test fun `failed dismissal keeps the notice visible`() {
        val state = Preferences(commitSucceeds = false)
        val config = Config(state.preferences)
        assertFalse(config.dismissMigrationNotice(100))
        assertTrue(config.migrationNoticeVisible(100))
    }

    @Test fun `restored connection history suppresses the notice without granting transport authority`() {
        val state = Preferences()
        state.preferences.edit().putBoolean("migration_notice_connection_seen", true).commit()
        val successor = Config(state.preferences)
        assertFalse(successor.migrationNoticeVisible(101))
        assertEquals("", successor.panelAssistantAuthority)
        assertEquals(0L, successor.panelAssistantUpdateOwnerSeenMs)
    }

    @Test fun `unrecognized authority is not a Panel Assistant connection`() {
        val config = Config(Preferences().preferences)
        config.setPanelAssistantAuthority("unrecognized")
        assertTrue(config.migrationNoticeVisible(100))
    }

    private class Preferences(private val commitSucceeds: Boolean = true) {
        private val values = mutableMapOf<String, Any?>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getString", "getInt", "getLong", "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { pending[args!![0] as String] = args[1]; proxy }
                    method.name == "apply" -> { values.putAll(pending); null }
                    method.name == "commit" -> { if (commitSucceeds) values.putAll(pending); commitSucceeds }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
