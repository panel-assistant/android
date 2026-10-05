package io.panelassistant.android

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * What the panel keeps of the integration's last accepted hello: the authority and the MQTT discovery
 * claim. Both are read on every bridge connect, so they are plain preference reads, and both setters
 * skip an unchanged value so a reconnecting session never rewrites the store.
 */
class ConfigPanelAssistantGrantTest {
    @Test fun theDiscoveryClaimIsEmptyBeforeAnySessionAndRoundTrips() {
        val prefs = CountingPreferences()
        val config = Config(prefs.instance)
        assertEquals("", config.panelAssistantMqttDiscovery)

        config.setPanelAssistantMqttDiscovery("withdraw")
        assertEquals("withdraw", prefs.values["panel_assistant_mqtt_discovery"])
        assertEquals("withdraw", Config(prefs.instance).panelAssistantMqttDiscovery)

        config.setPanelAssistantMqttDiscovery("announce")
        assertEquals("announce", config.panelAssistantMqttDiscovery)
        assertEquals(2, prefs.edits)
    }

    @Test fun anUnchangedDiscoveryClaimIsNotWrittenAgain() {
        val prefs = CountingPreferences(mapOf("panel_assistant_mqtt_discovery" to "withdraw"))
        val config = Config(prefs.instance)
        config.setPanelAssistantMqttDiscovery("withdraw")
        assertEquals(0, prefs.edits)
        config.setPanelAssistantMqttDiscovery("announce")
        assertEquals(1, prefs.edits)
    }

    @Test fun anUnchangedAuthorityIsNotWrittenAgain() {
        val prefs = CountingPreferences(mapOf("panel_assistant_authority" to "native"))
        val config = Config(prefs.instance)
        config.setPanelAssistantAuthority("native")
        assertEquals(0, prefs.edits)
        config.setPanelAssistantAuthority("shadow")
        assertEquals("shadow", prefs.values["panel_assistant_authority"])
        assertEquals(1, prefs.edits)
    }

    /** In-memory preferences that count how many editors were opened. */
    private class CountingPreferences(initial: Map<String, Any?> = emptyMap()) {
        val values: MutableMap<String, Any?> = initial.toMutableMap()
        var edits = 0
            private set

        val instance: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
                "getInt" -> values[args!![0]] as? Int ?: args[1]
                "getLong" -> values[args!![0]] as? Long ?: args[1]
                "getFloat" -> values[args!![0]] as? Float ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor().also { edits++ }
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "CountingPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val writes = LinkedHashMap<String, Any?>()
            val removals = LinkedHashSet<String>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when {
                    method.name.startsWith("put") -> editor.also {
                        writes[args!![0] as String] = args[1]
                        removals.remove(args[0])
                    }
                    method.name == "remove" -> editor.also {
                        writes.remove(args!![0] as String)
                        removals.add(args[0] as String)
                    }
                    method.name == "commit" || method.name == "apply" -> {
                        removals.forEach(values::remove)
                        values.putAll(writes)
                        if (method.name == "commit") true else null
                    }
                    method.name == "toString" -> "CountingEditor"
                    else -> error("unexpected Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
