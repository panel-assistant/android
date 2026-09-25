package io.github.maxlyth.hapaneld.mqtt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttNavigationSurfaceTest {
    // Source-text reason: pins MqttBridge.kt, deleted with the MQTT removal.
    @Test fun `navigation actions remain tombstoned but are not MQTT controls`() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
            File("../app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        ).first(File::isFile).readText()

        listOf("cmdAdminLauncher", "cmdBack", "cmdHome", "cmdLauncher", "cmdRecents").forEach {
            assertFalse("$it must not remain an MQTT action", source.contains("private val $it ="))
        }
        listOf("admin_launcher", "back", "home", "launcher", "recents").forEach { objectId ->
            assertTrue(
                "$objectId must remain in the historical tombstone superset",
                "\"button\" to \"${'$'}{panel}_${objectId}\"" in source,
            )
        }
    }
}
