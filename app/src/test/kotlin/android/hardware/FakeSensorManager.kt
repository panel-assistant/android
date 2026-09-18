package android.hardware

import android.os.Handler

/**
 * A `SensorManager` that declares a light sensor and controls whether activating it succeeds, so the
 * declared-but-dead part of #138 can be reproduced in a JVM unit test.
 *
 * It lives in `android.hardware` because `SensorManager`'s constructor is package-private; the
 * project has no Robolectric, and `ShadowSensorManager.setForceListenersToFail` would be global to
 * every sensor rather than the light one.
 */
class FakeSensorManager(private val activates: Boolean) : SensorManager() {
    val light: Sensor = allocate(Sensor::class.java)
    var lightRegistrations = 0
        private set

    override fun getDefaultSensor(type: Int): Sensor? = if (type == Sensor.TYPE_LIGHT) light else null

    override fun registerListener(
        listener: SensorEventListener?,
        sensor: Sensor?,
        samplingPeriodUs: Int,
        handler: Handler?,
    ): Boolean {
        if (sensor === light) lightRegistrations++
        return activates
    }

    override fun unregisterListener(listener: SensorEventListener?) = Unit

    override fun unregisterListener(listener: SensorEventListener?, sensor: Sensor?) = Unit

    override fun getSensorList(type: Int): List<Sensor> =
        if (type == Sensor.TYPE_LIGHT) listOf(light) else emptyList()

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(field.get(null), type) as T
        }
    }
}
