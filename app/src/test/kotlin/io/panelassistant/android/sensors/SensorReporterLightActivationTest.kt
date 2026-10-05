package io.panelassistant.android.sensors

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.hardware.FakeSensorManager
import android.hardware.SensorManager
import io.panelassistant.android.Config
import io.panelassistant.android.control.fakeProfile
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SensorReporter` against a `SensorManager` that declares a light sensor and refuses to activate it
 * — the YC-SM10P's `em3071x` (#138). Presence must not be mistaken for a working source.
 */
class SensorReporterLightActivationTest {
    private class FakeContext(
        private val files: File,
        private val prefs: SharedPreferences,
        private val sensors: SensorManager,
    ) : ContextWrapper(null) {
        private val resolver = object : ContentResolver(null) {}
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? =
            if (name == Context.SENSOR_SERVICE) sensors else null
        override fun getContentResolver(): ContentResolver = resolver
        override fun getNoBackupFilesDir(): File = files
        override fun getFilesDir(): File = files
        override fun getDatabasePath(name: String): File = File(files, name)
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
    }

    private fun reporter(activates: Boolean, hasProximity: Boolean = false): Pair<SensorReporter, FakeSensorManager> {
        val sensors = FakeSensorManager(activates, hasProximity)
        val prefs = proxyPreferences()
        val files = Files.createTempDirectory("sensor-reporter").toFile().also { it.deleteOnExit() }
        val context = FakeContext(files, prefs, sensors)
        return SensorReporter(context, newConfig(prefs, context.contentResolver), fakeProfile()) to sensors
    }

    @Test fun `a light sensor that refuses to activate is present but not available`() {
        val (reporter, sensors) = reporter(activates = false)

        // Presence is unchanged: the diagnostics row still reports the part the device tree declares.
        assertTrue(reporter.hasLight())
        // Optimistic until the answer is known, so a healthy panel does not churn its entity at boot.
        assertTrue(reporter.lightAvailable())

        reporter.start(onLux = {}, onProximity = { _, _, _ -> })

        assertEquals(1, sensors.lightRegistrations)
        // The advertised answer, which Capabilities.hasLight and therefore MQTT discovery reads.
        assertFalse(reporter.lightAvailable())
        assertTrue(reporter.hasLight())
        reporter.stop()
        // The verdict survives the run that reached it; a restart must not re-advertise a dead part.
        assertFalse(reporter.lightAvailable())
    }

    @Test fun `a light sensor that activates stays available while it acquires`() {
        val (reporter, sensors) = reporter(activates = true)

        reporter.start(onLux = {}, onProximity = { _, _, _ -> })

        assertEquals(1, sensors.lightRegistrations)
        assertTrue(reporter.lightAvailable())
        reporter.stop()
    }

    @Test fun `learned proximity is settled at once on a panel with no proximity source`() {
        val (reporter, _) = reporter(activates = true)
        assertEquals(false, reporter.learnedProximityState())
    }

    @Test fun `learned proximity is unsettled until the calibration loads, when it cannot be read, and once closed`() {
        val (reporter, _) = reporter(activates = true, hasProximity = true)
        // A false here would be stated to Home Assistant as a panel without the sensor, removing its entities.
        assertEquals(null, reporter.learnedProximityState())
        reporter.prepare()
        // The JVM has no working SQLite, so the stored calibration read fails here: the profile baseline
        // stands in, the signal reads not learned, and the settled answer must stay unknown.
        assertFalse(reporter.hasLearnedProximity())
        assertEquals(null, reporter.learnedProximityState())
        reporter.stop().get()
        assertEquals(null, reporter.learnedProximityState())
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(field.get(null), type) as T
        }

        fun newConfig(prefs: SharedPreferences, resolver: ContentResolver): Config {
            val constructor = Config::class.java.declaredConstructors.single {
                it.parameterTypes.contentEquals(
                    arrayOf(
                        SharedPreferences::class.java,
                        ContentResolver::class.java,
                        SharedPreferences::class.java,
                        SharedPreferences::class.java,
                        Resources::class.java,
                    ),
                )
            }
            constructor.isAccessible = true
            return constructor.newInstance(prefs, resolver, prefs, prefs, null) as Config
        }

        fun proxyPreferences(): SharedPreferences {
            val values = ConcurrentHashMap<String, Any>()
            return Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" ->
                        values[args!![0] as String] ?: args[1]
                    "contains" -> values.containsKey(args!![0] as String)
                    "edit" -> editor(values)
                    else -> null
                }
            } as SharedPreferences
        }

        private fun editor(values: MutableMap<String, Any>): SharedPreferences.Editor =
            Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                        val value = args!![1]
                        if (value == null) values.remove(args[0] as String) else values[args[0] as String] = value
                        proxy
                    }
                    "remove" -> { values.remove(args!![0] as String); proxy }
                    "clear" -> { values.clear(); proxy }
                    "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
    }
}
