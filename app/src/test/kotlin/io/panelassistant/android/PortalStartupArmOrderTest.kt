package io.panelassistant.android

import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.EvdevDeviceInspector
import io.panelassistant.android.device.profile.ProfilePreferences
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.testsupport.scratchDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** A genuine Portal boot shows the vendor's overlay page within seconds: arming must not wait for older work. */
class PortalStartupArmOrderTest {
    @Test fun theDismissalIsArmedBeforeSettingsMigrationsAndPredecessorTeardown() {
        val directory = scratchDirectory("portal-arm-order").toFile()
        val values = mutableMapOf<String, Any>()
        val preferences = object : ProfilePreferences {
            override fun getString(key: String, default: String) = values[key] as? String ?: default
            override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
            override fun put(vararg entries: Pair<String, Any?>): Boolean {
                entries.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
        }
        var armed = false
        val service = PaneldService()
        service.startupProfileRegistry = {
            RuntimeProfileRegistry(
                filesDir = directory, preferences = preferences, bundledLoader = { emptyMap() },
                facts = DeviceFacts("unmatched", "unmatched", ""), coreVersion = "1.0.0", clock = { 1000L },
                evdevInspector = EvdevDeviceInspector { null },
            )
        }
        service.portalStartupOverlayFactory = { _, _ -> armed = true; null }
        try {
            // The JVM stubs cannot build Config, so the lane stops at the first settings migration.
            // Arming has to have happened by then, ahead of migrations and predecessor teardown.
            runCatching { runBlocking { service.initializeOnStartupLane() } }
            assertTrue("the dismissal must be armed before the settings migrations", armed)
        } finally {
            (PaneldService::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(service) as CoroutineScope).cancel()
            directory.deleteRecursively()
        }
    }
}
