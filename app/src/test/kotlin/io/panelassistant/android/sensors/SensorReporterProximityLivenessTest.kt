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
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.profile.ProfileProximityCalibration
import io.panelassistant.android.testsupport.scratchDirectory
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Drives the service-owned reporter through Android registration, callbacks and handler deadlines. */
class SensorReporterProximityLivenessTest {
    private val reporters = mutableListOf<SensorReporter>()

    @Before fun beginTimeline() = SystemClock.beginSensorTest()
    @After fun closeTimeline() {
        try { reporters.forEach { it.stop().get() } } finally { SystemClock.endSensorTest() }
    }

    @Test fun namedAndroidPulsesFeedTheReporterAndDoNotRegisterTheHal() {
        val xl = io.panelassistant.android.device.profile.BundledProfileFixtures.profile("shelly-wall-display-xl")
        val rig = rig(deviceProfile = xl)
        assertEquals(0, rig.sensors.successfulRegistrations)
        assertTrue(rig.reporter.hasProximity())
        val buttons = mutableListOf<String>()
        val subscription = io.panelassistant.android.input.ButtonBus.subscribe(buttons::add)
        try {
            fun pulse(name: String, code: Int) {
                io.panelassistant.android.input.AccessibilityKeyRouter.dispatch(name, code, true, 0, "KEYCODE_F${code - 58}")
                advance(0)
            }
            pulse("gpio_keys", 64)
            assertEquals(0.0, rig.json().getDouble("raw"), 0.0)
            pulse("other", 63)
            assertEquals(0.0, rig.json().getDouble("raw"), 0.0)
            pulse("gpio_keys", 63)
            assertEquals(1.0, rig.json().getDouble("raw"), 0.0)
            assertTrue(buttons.isEmpty())
            rig.reporter.stop().get()
            pulse("gpio_keys", 64)
            assertEquals(1.0, rig.json().getDouble("raw"), 0.0)
            assertTrue("stopped sensor pulses must not become buttons", buttons.isEmpty())
        } finally { subscription.close() }
    }

    @Test fun denseOnChangePlateauSurvivesSilenceAndThenReportsTheNextChange() {
        val rig = rig()
        rig.densePlateau(0f)
        rig.assertAvailable(near = false, raw = 0f)
        val reportsBeforeSilence = rig.proximity.toList()

        advance(65_000)
        rig.assertAvailable(near = false, raw = 0f)
        assertEquals(reportsBeforeSilence, rig.proximity)
        assertTrue(rig.json().getLong("age_s") >= 60)

        // The virtual elapsed clock jumped while real wall time did not. Re-pair the timestamp gate
        // with the unchanged value before delivering the fresh physical change on elapsed time.
        rig.sensors.emit(0f)
        advance(1)
        rig.sensors.emit(1f)
        rig.assertAvailable(near = true, raw = 1f)
        assertEquals(true to 100, rig.proximity.last())
    }

    @Test fun silentSuccessfulReregistrationRetainsTheEstablishedOnChangeReading() {
        val rig = rig()
        rig.densePlateau(1f)
        val reportsBeforeSilence = rig.proximity.toList()

        // Includes stale detection, re-registration acquisition timeout and a later periodic probe.
        advance(16 * 60_000)

        rig.assertAvailable(near = true, raw = 1f)
        assertEquals(reportsBeforeSilence, rig.proximity)
        assertTrue(rig.json().getLong("age_s") >= 15 * 60)
    }

    @Test fun aChangedLastDenseCallbackWithoutAStablePlateauStillBecomesUnavailable() {
        val rig = rig()
        rig.samples(0f, 8)
        advance(1)
        rig.sensors.emit(1f)
        rig.assertAvailable(near = true, raw = 1f)

        advance(65_000)

        rig.assertUnavailable()
        assertEquals(null to null, rig.proximity.last())
    }

    @Test fun continuousHalStillExpiresWhenItsDenseStreamStops() {
        val rig = rig(Sensor.REPORTING_MODE_CONTINUOUS)
        rig.densePlateau(0f)
        rig.assertAvailable(near = false, raw = 0f)

        advance(65_000)

        rig.assertUnavailable()
        assertEquals(null to null, rig.proximity.last())
    }

    @Test fun registrationWithoutAnyInitialReadingNeverBecomesAvailable() {
        val rig = rig()

        advance(16 * 60_000)

        rig.assertUnavailable()
        assertEquals(listOf(null to null, null to null), rig.proximity)
    }

    @Test fun initialRegistrationRefusalStaysUnavailable() {
        val rig = rig(acceptsRegistration = false)
        advance(6_000)
        rig.assertUnavailable()
        assertEquals(listOf(null to null), rig.proximity)
    }

    @Test fun refusedReregistrationRevokesAnEstablishedOnChangePlateau() {
        val rig = rig()
        rig.samples(0f, 2)
        advance(3_000)
        rig.assertAvailable(near = false, raw = 0f)
        rig.sensors.acceptsRegistration = false

        advance(16 * 60_000)

        rig.assertUnavailable()
        assertEquals(null to null, rig.proximity.last())
    }

    @Test fun invalidReadingRevokesTheRetainedPlateau() {
        val rig = rig()
        rig.densePlateau(0f)
        advance(65_000)
        rig.assertAvailable(near = false, raw = 0f)

        rig.sensors.emit(Float.NaN)

        rig.assertUnavailable()
        assertEquals(null to null, rig.proximity.last())
    }

    @Test fun aFreshApproachDuringAnEstablishedPlateausProbeCanStillWakeThePanel() {
        val rig = rig()
        rig.samples(0f, 2)
        advance(3_000)
        // Delivery during re-registration observes the actual probe boundary without assuming how
        // much real wall time fixture setup consumed. The first unchanged event re-pairs the clocks
        // after the virtual jump; the fresh changed event follows while acquisition still awaits.
        rig.sensors.onSuccessfulRegistration = { count ->
            if (count == 2) {
                rig.sensors.emit(0f)
                advance(1)
                rig.sensors.emit(1f)
            }
        }
        assertEquals(1, rig.sensors.successfulRegistrations)
        advance(15 * 60_000)
        assertEquals(2, rig.sensors.successfulRegistrations)

        rig.assertAvailable(near = true, raw = 1f)
        assertEquals(1, rig.approaches)
        assertEquals(true to 100, rig.proximity.last())
    }

    private fun rig(
        mode: Int = Sensor.REPORTING_MODE_ON_CHANGE,
        acceptsRegistration: Boolean = true,
        deviceProfile: DeviceProfile? = null,
    ): Rig {
        val sensors = ProximitySensorManager(mode).also { it.acceptsRegistration = acceptsRegistration }
        val files = scratchDirectory("proximity-liveness").toFile()
        val prefs = preferences()
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
        val profile = object : DeviceProfile by fakeProfile(id = "generic") {
            override val proximityCalibration = ProfileProximityCalibration(
                revision = 1, mode = "binary", clearRaw = 0f, nearRaw = 1f,
                verification = "Deterministic binary HAL fixture", formatVersion = 2,
                debounceMs = 0, clearArmMs = 0, minimumNearMs = 0,
            )
        }
        val reporter = SensorReporter(context, config, deviceProfile ?: profile).also { reporters += it; it.prepare() }
        return Rig(reporter, sensors).also { rig ->
            reporter.start(onLux = {}, onProximity = { near, level, _ -> rig.proximity += near to level },
                onPresenceApproach = { rig.approaches++ })
            advance(0)
        }
    }

    private class Rig(val reporter: SensorReporter, val sensors: ProximitySensorManager) {
        val proximity = mutableListOf<Pair<Boolean?, Int?>>()
        var approaches = 0
        fun json() = JSONObject(reporter.proximityJson())
        fun samples(raw: Float, count: Int) {
            repeat(count) { sensors.emit(raw); advance(1) }
        }
        fun densePlateau(raw: Float) { samples(raw, 8); advance(3_000) }
        fun assertAvailable(near: Boolean, raw: Float) {
            assertEquals("healthy", json().getString("health"))
            assertEquals("ready", JSONObject(reporter.proximityStatusJson()!!).getString("state"))
            assertTrue(reporter.proximityPresenceReady())
            assertEquals(near, reporter.proximityPresenceNear())
            assertEquals(raw.toDouble(), json().getDouble("raw"), 0.0)
            assertEquals(near, json().getBoolean("near"))
        }
        fun assertUnavailable() {
            assertEquals("source_unavailable", json().getString("health"))
            assertEquals("source_unavailable", JSONObject(reporter.proximityStatusJson()!!).getString("state"))
            assertFalse(reporter.proximityPresenceReady())
        }
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
