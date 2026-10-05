package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.persistence.SqliteStatePreferences
import io.panelassistant.android.persistence.StateMutation
import io.panelassistant.android.persistence.StateNamespacePersistence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class ConfigFloatReadBackTest {
    private val exposure = requireNotNull(SettingsRegistry.spec("camera_exposure"))
    private val roomOffset = requireNotNull(SettingsRegistry.spec("room_temp_offset"))

    @Test fun legacyOutOfRangeFloatsReadBackAsTheCanonicalDefault() {
        listOf(-3f, 3f).forEach { persisted ->
            val config = configWith("camera_exposure", persisted)
            assertEquals("persisted=$persisted", "0", config.getRaw(exposure))
            assertEquals("runtime persisted=$persisted", 0f, config.cameraExposureEv)
        }
    }

    @Test fun legacyNonFiniteFloatsReadBackAsTheCanonicalDefault() {
        listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY).forEach { persisted ->
            val config = configWith("room_temp_offset", persisted)
            assertEquals("persisted=$persisted", "0", config.getRaw(roomOffset))
            assertEquals("runtime persisted=$persisted", 0f, config.roomTempOffsetC)
        }
    }

    @Test fun legacyRoomOffsetsOutsideTheCurrentRangeCannotDivergeFromReadBack() {
        listOf(-30f, 30f).forEach { persisted ->
            val config = configWith("room_temp_offset", persisted)
            assertEquals("persisted=$persisted", "0", config.getRaw(roomOffset))
            assertEquals("runtime persisted=$persisted", 0f, config.roomTempOffsetC)
        }
    }

    @Test fun validPersistedFloatsRetainStorageDomainCanonicalization() {
        mapOf(
            -0.0f to "0",
            0.123456789f to "0.12345679",
            2f to "2",
        ).forEach { (persisted, expected) ->
            assertEquals("persisted=$persisted", expected, configWith("camera_exposure", persisted).getRaw(exposure))
        }
    }

    @Test fun restoredInvalidFloatUsesTheSameEffectiveDefaultThroughTheProductionPreferenceOwner() {
        val writer = Executors.newSingleThreadExecutor()
        try {
            val persistence = RestoredState(mapOf("room_temp_offset" to Float.NaN))
            val config = Config(SqliteStatePreferences(persistence, writer))

            assertEquals("0", config.getRaw(roomOffset))
            assertEquals(0f, config.roomTempOffsetC)

            config.setRaw(roomOffset, "1.5")
            assertEquals("1.5", config.getRaw(roomOffset))
            assertEquals(1.5f, config.roomTempOffsetC)
        } finally {
            writer.shutdownNow()
        }
    }

    private fun configWith(key: String, value: Float): Config = Config(floatPreferences(key, value))

    private fun floatPreferences(key: String, value: Float): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "contains" -> args!![0] == key
            "getFloat" -> if (args!![0] == key) value else args[1]
            "toString" -> "FloatReadBackPreferences"
            else -> error("unexpected SharedPreferences call: ${method.name}")
        }
    } as SharedPreferences

    private class RestoredState(initial: Map<String, Any>) : StateNamespacePersistence {
        private val values = initial.toMutableMap()

        override fun initialize(): Map<String, Any> = values.toMap()

        override fun persist(mutation: StateMutation): Boolean {
            if (mutation.clear) values.clear()
            mutation.changes.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun replace(snapshot: Map<String, Any>): Boolean {
            values.clear()
            values.putAll(snapshot)
            return true
        }
    }
}
