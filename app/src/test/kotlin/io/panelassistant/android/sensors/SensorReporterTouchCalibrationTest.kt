package io.panelassistant.android.sensors

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.hardware.ProximitySensorManager
import android.hardware.Sensor
import android.os.SystemClock
import io.panelassistant.android.Config
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The service-owned reporter calibrates an uncalibrated ranged HAL from the screen touches it is handed. */
class SensorReporterTouchCalibrationTest {
    private val reporters = mutableListOf<SensorReporter>()
    private val touchListeners = mutableListOf<() -> Unit>()

    @Before fun beginTimeline() = SystemClock.beginSensorTest()
    @After fun closeTimeline() {
        try { reporters.forEach { it.stop().get() } } finally { SystemClock.endSensorTest() }
    }

    @Test fun automaticPresenceCalibratesItselfFromRealTouchesOnTheOfficePanelCapture() {
        val (reporter, sensors) = rig(mapOf("presence_mode" to "automatic"))
        assertFalse(reporter.hasLearnedProximity())
        val replay = ProximityTouchReplay.load("office-taps.csv")
        var clock = 0L
        for ((at, raw) in replay.events()) {
            advance((at - clock).coerceAtLeast(0)); clock = maxOf(clock, at) + 1
            if (raw == null) touchListeners.forEach { it() } else sensors.deliver(raw)
        }
        assertTrue("ranged HAL", reporter.hasRangedProximity())
                assertTrue("calibrated from touches", reporter.hasLearnedProximity())
        assertTrue(reporter.proximityPresenceReady())
        // A second visit is recognised: the calibration learned from the first touches tracks the person.
        var checked: Boolean? = null
        for ((at, raw) in replay.events()) {
            advance((at + 200_000 - clock).coerceAtLeast(0)); clock = maxOf(clock, at + 200_000) + 1
            if (raw != null) sensors.deliver(raw)
            if (checked == null && at >= replay.touches[1] + 3_000) checked = reporter.proximityPresenceNear()
        }
        assertEquals("present at the panel on the second visit", true, checked)
    }

    @Test fun manualPresenceIgnoresTouches() {
        val (reporter, sensors) = rig(mapOf("presence_mode" to "manual"))
        val replay = ProximityTouchReplay.load("office-taps.csv")
        var clock = 0L
        for ((at, raw) in replay.events()) {
            advance((at - clock).coerceAtLeast(0)); clock = maxOf(clock, at) + 1
            if (raw == null) touchListeners.forEach { it() } else sensors.deliver(raw)
        }
        assertFalse(reporter.hasLearnedProximity())
        assertEquals(1, touchListeners.size)
    }

    /**
     * The reporter's freshness gate pairs the sensor timestamp with both clocks, and the test timeline moves
     * only the elapsed one; the first reading after a jump is therefore judged stale. Repeat each reading a
     * millisecond later, as a dense HAL does, so the replayed value is admitted as fresh.
     */
    private fun ProximitySensorManager.deliver(raw: Float) { emit(raw); advance(1); emit(raw) }

    private fun rig(stored: Map<String, String>): Pair<SensorReporter, ProximitySensorManager> {
        val sensors = ProximitySensorManager(Sensor.REPORTING_MODE_CONTINUOUS)
        val files = scratchDirectory("proximity-touch").toFile()
        val prefs = preferences()
        prefs.edit().apply { stored.forEach { (k, v) -> putString(k, v) } }.commit()
        val context = object : ContextWrapper(null) {
            private val resolver = object : ContentResolver(null) {}
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = if (name == Context.SENSOR_SERVICE) sensors else null
            override fun getContentResolver(): ContentResolver = resolver
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(files, name)
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
        }
        val constructor = Config::class.java.declaredConstructors.single {
            it.parameterTypes.contentEquals(arrayOf(
                SharedPreferences::class.java, ContentResolver::class.java,
                SharedPreferences::class.java, SharedPreferences::class.java, Resources::class.java,
            ))
        }.apply { isAccessible = true }
        val config = constructor.newInstance(prefs, context.contentResolver, prefs, prefs, null) as Config
        val reporter = SensorReporter(context, config, fakeProfile(id = "generic"), subscribeToTouches = { listener ->
            touchListeners += listener
            AutoCloseable { touchListeners.remove(listener) }
        }).also { reporters += it; it.prepare() }
        reporter.start(onLux = {}, onProximity = { _, _, _ -> })
        advance(0)
        return reporter to sensors
    }

    private companion object {
        fun advance(millis: Long) = SystemClock.advanceSensorTest(millis)
        fun preferences(): SharedPreferences {
            val values = mutableMapOf<String, Any>()
            val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                        val key = args!![0] as String
                        args[1]?.let { values[key] = it } ?: values.remove(key)
                        proxy
                    }
                    "remove" -> { values.remove(args!![0] as String); proxy }
                    "clear" -> { values.clear(); proxy }
                    "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getAll" -> values.toMap()
                    "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" -> values[args!![0]] ?: args[1]
                    "contains" -> values.containsKey(args!![0])
                    "edit" -> editor
                    else -> null
                }
            } as SharedPreferences
        }
    }
}
