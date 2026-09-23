package io.github.maxlyth.hapaneld

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.dashboard.HomeDashboardLaunchCache
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted launch cache through [Config]: identity scoping, fail-closed reads and the
 * owner-raced write refusal. Identity changes make the stored row UNREADABLE (owner mismatch)
 * rather than depending on cleanup code having run — the row may physically remain, and that is
 * asserted here so the mechanism is the one under test.
 */
class HomeDashboardLaunchCacheConfigTest {

    private fun seeded(vararg overrides: Pair<String, Any?>): Pair<Config, MutableMap<String, Any?>> {
        val values = mutableMapOf<String, Any?>(
            "ha_url" to "https://ha.example:8123",
            "ha_token" to "short-lived",
            "ha_refresh_token" to "refresh-a",
            "ha_client_id" to "client-a",
            "home_dashboard" to "",
        )
        overrides.forEach { (key, value) -> values[key] = value }
        return Config(fakePreferences(values)) to values
    }

    @Test fun `a resolved path persists and reads back for the same owner`() {
        val (config, values) = seeded()
        assertNull(config.cachedHomeDashboardLaunchPath())
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(config.homeDashboardLaunchOwner(), "/alpha"))
        assertEquals("/alpha", config.cachedHomeDashboardLaunchPath())
        assertEquals("/alpha", values["dashboard_launch_path"])
    }

    @Test fun `a write with a superseded owner fingerprint is refused`() {
        val (config, values) = seeded()
        val staleOwner = config.homeDashboardLaunchOwner()
        values["home_dashboard"] = "/beta" // the setting changed while the resolution was in flight
        assertFalse(config.setHomeDashboardLaunchPathIfOwned(staleOwner, "/alpha"))
        assertNull(values["dashboard_launch_path"])
        assertNull(config.cachedHomeDashboardLaunchPath())
    }

    @Test fun `an explicit home dashboard change makes the cache unreadable without cleanup`() {
        val (config, values) = seeded()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(config.homeDashboardLaunchOwner(), "/alpha"))
        values["home_dashboard"] = "/beta"
        assertNull(config.cachedHomeDashboardLaunchPath())
        assertEquals("/alpha", values["dashboard_launch_path"]) // row remains; identity gates it
    }

    @Test fun `a server change never reuses the prior instance cache`() {
        val (config, values) = seeded()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(config.homeDashboardLaunchOwner(), "/alpha"))
        values["ha_url"] = "https://moved.example:8123"
        assertNull(config.cachedHomeDashboardLaunchPath())
    }

    @Test fun `an account change never reuses the prior identity cache`() {
        val (config, values) = seeded()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(config.homeDashboardLaunchOwner(), "/alpha"))
        values["ha_refresh_token"] = "refresh-b" // re-login / different HA user
        assertNull(config.cachedHomeDashboardLaunchPath())
    }

    @Test fun `a static token panel keys its cache on that token`() {
        val (config, values) = seeded("ha_refresh_token" to "", "ha_token" to "long-lived-a")
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(config.homeDashboardLaunchOwner(), "/alpha"))
        assertEquals("/alpha", config.cachedHomeDashboardLaunchPath())
        values["ha_token"] = "long-lived-b"
        assertNull(config.cachedHomeDashboardLaunchPath())
    }

    @Test fun `a corrupt stored value fails closed to a live resolution`() {
        val (config, values) = seeded()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(config.homeDashboardLaunchOwner(), "/alpha"))
        values["dashboard_launch_path"] = "//evil.example/pwn"
        assertNull(config.cachedHomeDashboardLaunchPath())
    }

    @Test fun `clearing requires the live owner and removes the row`() {
        val (config, values) = seeded()
        val owner = config.homeDashboardLaunchOwner()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(owner, "/alpha"))
        assertFalse(config.clearHomeDashboardLaunchPathIfOwned("not-the-owner"))
        assertEquals("/alpha", config.cachedHomeDashboardLaunchPath())
        assertTrue(config.clearHomeDashboardLaunchPathIfOwned(owner))
        assertNull(config.cachedHomeDashboardLaunchPath())
        assertNull(values["dashboard_launch_path"])
        assertNull(values["dashboard_launch_path_owner"])
    }

    @Test fun `an over-long resolved path is refused rather than truncated into a new route`() {
        val (config, values) = seeded()
        val owner = config.homeDashboardLaunchOwner()
        val overLong = "/alpha/" + "a".repeat(HomeDashboardLaunchCache.MAX_STORED_PATH_CHARS)
        assertFalse(config.setHomeDashboardLaunchPathIfOwned(owner, overLong))
        // Truncation would have manufactured a route Home Assistant never validated.
        assertNull(values["dashboard_launch_path"])
        assertNull(config.cachedHomeDashboardLaunchPath())
    }

    @Test fun `a path at exactly the bound is still stored verbatim`() {
        val (config, values) = seeded()
        val owner = config.homeDashboardLaunchOwner()
        val exact = "/o" + "a".repeat(HomeDashboardLaunchCache.MAX_STORED_PATH_CHARS - 2)
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(owner, exact))
        assertEquals(exact, values["dashboard_launch_path"])
    }

    @Test fun `an unchanged confirmation does not rewrite the row`() {
        val values = mutableMapOf<String, Any?>(
            "ha_url" to "https://ha.example:8123",
            "ha_token" to "short-lived",
            "ha_refresh_token" to "refresh-a",
            "ha_client_id" to "client-a",
            "home_dashboard" to "",
        )
        val editorOpens = java.util.concurrent.atomic.AtomicInteger()
        val config = Config(fakePreferences(values, editorOpens))
        val owner = config.homeDashboardLaunchOwner()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(owner, "/alpha"))
        val opensAfterFirstWrite = editorOpens.get()
        assertTrue(config.setHomeDashboardLaunchPathIfOwned(owner, "/alpha"))
        assertEquals(opensAfterFirstWrite, editorOpens.get()) // confirmation was a read, not a write
        assertEquals("/alpha", config.cachedHomeDashboardLaunchPath())
    }

    private fun fakePreferences(
        values: MutableMap<String, Any?>,
        editorOpens: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger(),
    ): SharedPreferences {
        lateinit var prefs: SharedPreferences
        prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getInt" -> values[args!![0]] as? Int ?: args[1]
                "getLong" -> values[args!![0]] as? Long ?: args[1]
                "getFloat" -> values[args!![0]] as? Float ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> fakeEditor(values).also { editorOpens.incrementAndGet() }
                "registerOnSharedPreferenceChangeListener",
                "unregisterOnSharedPreferenceChangeListener",
                -> null
                "toString" -> "FakeSharedPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        return prefs
    }

    private fun fakeEditor(values: MutableMap<String, Any?>): SharedPreferences.Editor {
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
                    val key = args!![0] as String
                    writes.remove(key)
                    removals.add(key)
                }
                method.name == "commit" -> {
                    removals.forEach(values::remove)
                    values.putAll(writes)
                    true
                }
                method.name == "apply" -> {
                    removals.forEach(values::remove)
                    values.putAll(writes)
                    null
                }
                method.name == "toString" -> "FakeEditor"
                else -> error("unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
