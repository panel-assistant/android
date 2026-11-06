package android.hardware

import android.os.Handler
import android.os.SystemClock

/** An on-change HAL may accept registration yet stay silent while its reading remains unchanged. */
class ProximitySensorManager(reportingMode: Int) : SensorManager() {
    val proximity = Sensor(Sensor.TYPE_PROXIMITY, reportingMode)
    var acceptsRegistration = true
    var successfulRegistrations = 0
        private set
    var onSuccessfulRegistration: ((Int) -> Unit)? = null
    private var listener: SensorEventListener? = null
    private var handler: Handler? = null

    override fun getDefaultSensor(type: Int): Sensor? = proximity.takeIf { type == Sensor.TYPE_PROXIMITY }
    override fun getSensorList(type: Int): List<Sensor> =
        if (type == Sensor.TYPE_PROXIMITY) listOf(proximity) else emptyList()

    override fun registerListener(listener: SensorEventListener?, sensor: Sensor?, samplingPeriodUs: Int, handler: Handler?): Boolean {
        if (sensor !== proximity || !acceptsRegistration) return false
        successfulRegistrations++
        this.listener = listener
        this.handler = handler
        onSuccessfulRegistration?.invoke(successfulRegistrations)
        return true
    }

    override fun unregisterListener(listener: SensorEventListener?) {
        if (this.listener === listener) this.listener = null
    }
    override fun unregisterListener(listener: SensorEventListener?, sensor: Sensor?) {
        if (sensor === proximity) unregisterListener(listener)
    }

    fun emit(raw: Float) {
        val target = listener ?: return
        val event = SensorEvent(proximity, raw, SystemClock.elapsedRealtimeNanos())
        check(handler?.post { target.onSensorChanged(event) } == true)
        SystemClock.advanceSensorTest(0)
    }
}
