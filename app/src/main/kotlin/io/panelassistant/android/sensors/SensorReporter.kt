package io.panelassistant.android.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.metrics.RoomClimate
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal fun formatSensorValue(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString()
    else String.format(Locale.ROOT, "%.1f", value)

internal data class ProximitySourcePolicy(
    val sparseLearning: Boolean,
    val onChangeHalLiveness: Boolean,
    val legacySeedEligible: Boolean,
)

internal enum class ProximityAcquisition {
    VI530X,
    GPIO,
    ANDROID_HAL,
    STK_RAW,
    ABSENT,
}

internal fun proximityAcquisition(
    hasVi530x: Boolean,
    proximityGpio: Int?,
    hasHal: Boolean,
    hasStkRaw: Boolean = false,
): ProximityAcquisition = when {
    hasVi530x -> ProximityAcquisition.VI530X
    proximityGpio != null -> ProximityAcquisition.GPIO
    hasHal && hasStkRaw -> ProximityAcquisition.STK_RAW
    hasHal -> ProximityAcquisition.ANDROID_HAL
    else -> ProximityAcquisition.ABSENT
}

/** HAL delivery metadata selects liveness and legacy-seed safety, never sparse learning: vendor HALs
 * commonly claim on-change while emitting densely. Reporting sparsity is classified from live cadence. */
internal fun proximitySourcePolicy(
    acquisition: ProximityAcquisition,
    halReportingMode: Int?,
): ProximitySourcePolicy = ProximitySourcePolicy(
    sparseLearning = acquisition == ProximityAcquisition.GPIO,
    onChangeHalLiveness = acquisition == ProximityAcquisition.ANDROID_HAL &&
        halReportingMode == Sensor.REPORTING_MODE_ON_CHANGE,
    legacySeedEligible = acquisition == ProximityAcquisition.ANDROID_HAL &&
        halReportingMode == Sensor.REPORTING_MODE_CONTINUOUS,
)

internal fun proximityReportingSparse(
    sparseLearning: Boolean,
    cadenceClassified: Boolean,
    continuousCadenceConfirmed: Boolean,
): Boolean = sparseLearning || cadenceClassified && !continuousCadenceConfirmed

/** A normal on-change wave is two callbacks. Four within the three-second window is the first
 * sustained cadence that can exceed the fleet's one-report-per-second numeric budget. */
internal fun proximityCadenceWindowIsContinuous(sampleCount: Int): Boolean = sampleCount >= 4

internal data class ProximityCadenceRecovery(
    val cadenceClassified: Boolean,
    val continuousCadenceConfirmed: Boolean,
    val sampleCount: Int,
)

/** A stale dense stream must leave the healthy cadence state before HAL re-registration can run. */
internal fun proximityCadenceAfterStale(): ProximityCadenceRecovery = ProximityCadenceRecovery(
    cadenceClassified = true,
    continuousCadenceConfirmed = false,
    sampleCount = 0,
)

internal fun proximityNeedsHalLivenessProbe(
    acquisition: ProximityAcquisition,
    onChangeHalLiveness: Boolean,
    cadenceClassified: Boolean,
    continuousCadenceConfirmed: Boolean,
): Boolean = acquisition == ProximityAcquisition.ANDROID_HAL && !continuousCadenceConfirmed &&
    (onChangeHalLiveness || cadenceClassified)

internal enum class EnvironmentalSensorUse {
    ABSENT,
    PUBLISH,
    ACTIVATE_ONLY,
}

/** TPA10 room climate is published from the CHT8305 helper, but its vendor kernel driver only
 * refreshes the helper-facing input axes while Android has registered the corresponding sensors. */
internal fun environmentalSensorUse(
    hasCht8305: Boolean,
    sensorPresent: Boolean,
): EnvironmentalSensorUse = when {
    !sensorPresent -> EnvironmentalSensorUse.ABSENT
    hasCht8305 -> EnvironmentalSensorUse.ACTIVATE_ONLY
    else -> EnvironmentalSensorUse.PUBLISH
}

internal fun environmentalSensorPublishes(use: EnvironmentalSensorUse): Boolean =
    use == EnvironmentalSensorUse.PUBLISH

internal fun environmentalEndpointJson(
    use: EnvironmentalSensorUse,
    androidValue: Float,
    androidAgeSeconds: Long?,
    helperValue: Double?,
    activationState: ActivationRegistrationState?,
): String {
    if (use == EnvironmentalSensorUse.ACTIVATE_ONLY) {
        val value = helperValue?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "null"
        val state = (activationState ?: ActivationRegistrationState.IDLE).name.lowercase(Locale.ROOT)
        return "\"present\":true,\"value\":$value,\"source\":\"helper\",\"activation\":\"$state\""
    }
    if (use == EnvironmentalSensorUse.ABSENT) return "\"present\":false"
    val value = if (androidValue.isNaN()) "null" else formatSensorValue(androidValue)
    return "\"present\":true,\"value\":$value,\"age_s\":${androidAgeSeconds ?: "null"},\"source\":\"android\""
}

/**
 * Reports standard Android environmental sensors and feeds every proximity source through one
 * hardware-neutral fixed calibration. Device-native proximity values never become HA state: HA sees only a
 * calibrated binary and, when trustworthy, a fleet-normalized 0 (far) to 100 (near) level.
 */
class SensorReporter(
    context: Context,
    private val config: Config,
    private val profile: DeviceProfile,
) {
    private val appContext = context.applicationContext
    private val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val lightAvailability = LightAvailabilityTracker(
        present = lightSensor != null,
        reported = config.lightSensorReported,
        // The generic fallback knows nothing about the hardware; a specific profile naming no light
        // technology declares that the panel has none (the WF1589T).
        declaredAbsent = profile.id != "generic" && profile.lightTech == null,
        onFirstReading = { config.lightSensorReported = true },
        acquireTimeoutMs = ON_CHANGE_ACQUIRE_TIMEOUT_MS,
        schedule = { delayMs, expire ->
            val handler = sensorHandler
            val task = Runnable(expire)
            if (handler == null) ActivationRetryCancellation {} else {
                handler.postDelayed(task, delayMs)
                ActivationRetryCancellation { handler.removeCallbacks(task) }
            }
        },
        onChange = { notifyLightAvailability() },
    )
    private val proximitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val hasCht8305: Boolean = profile.hasCht8305
    private val tempSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humiditySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
    private val tempUse = environmentalSensorUse(hasCht8305, tempSensor != null)
    private val humidityUse = environmentalSensorUse(hasCht8305, humiditySensor != null)
    private val roomClimateMetrics: PanelMetrics? = if (hasCht8305) PanelMetrics() else null
    private val proximityGpio: Int? = profile.proximityGpio
    private val stkRawReader = StkRawProximityReader()
    private var stkRawExecutor: ScheduledExecutorService? = null
    private var stkRawGate: StkRawProximityGate? = null
    private var stkRawWatchdog: Runnable? = null
    private val proximityAcquisition = proximityAcquisition(
        hasVi530x = profile.hasVi530x,
        proximityGpio = proximityGpio,
        hasHal = proximitySensor != null,
        hasStkRaw = stkRawReader.isPresent(),
    )
    private val proximityPolicy = proximitySourcePolicy(
        proximityAcquisition,
        proximitySensor?.reportingMode,
    )
    @Volatile private var proximityRuntime: ProximityCalibrationRuntime? = null
    @Volatile private var learnedProximityListener: (() -> Unit)? = null
    private var lightAvailabilityListener: (() -> Unit)? = null
    @Volatile private var lastLearnedEligibility = false
    private var proximityPrepared = false

    @Volatile private var liveLux = Float.NaN
    @Volatile private var liveLuxAt = 0L
    @Volatile private var liveTemp = Float.NaN
    @Volatile private var liveTempAt = 0L
    @Volatile private var liveHumid = Float.NaN
    @Volatile private var liveHumidAt = 0L
    @Volatile private var liveProximityAt = 0L
    @Volatile private var lastRaw = Float.NaN
    @Volatile private var activeRun: SensorRunCallbacks? = null
    private var listener: SensorEventListener? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var tempActivation: ActivationRegistrationLifecycle? = null
    private var humidityActivation: ActivationRegistrationLifecycle? = null
    private var roomClimateRefresh: AsyncRoomClimateSnapshot? = null
    private var roomClimateExecutor: ScheduledExecutorService? = null
    private var gpioClient: GpioProximityClient? = null
    private var vi530xClient: Vi530xProximityClient? = null
    private var proximitySampleCount = 0
    private var cadenceClassified = false
    private var continuousCadenceConfirmed = false
    private var cadenceCheckScheduled = false
    private var staleCheckScheduled = false
    private var onChangeProbeScheduled = false
    private var onChangeProbeAwaiting = false
    private var calibrationTickScheduled = false
    private var proximityReceived = false
    private val proximityTimestampGate = ProximityTimestampGate()

    private val calibrationTick = object : Runnable {
        override fun run() {
            calibrationTickScheduled = false
            val run = activeRun ?: return
            if (!run.isOpen()) return
            val now = SystemClock.elapsedRealtime()
            if (stkRawGate?.expire(now) == true) deliverUnavailable(run)
            proximityRuntime?.tick(now, reportingSparse())?.let { deliverProximity(it, run) }
            scheduleCalibrationTick()
        }
    }

    private fun scheduleCalibrationTick() {
        if (calibrationTickScheduled || proximityRuntime?.needsTick() != true) return
        val handler = sensorHandler ?: return
        calibrationTickScheduled = true
        handler.postDelayed(calibrationTick, 100L)
    }

    private val cadenceCheck = Runnable {
        cadenceCheckScheduled = false
        val run = activeRun ?: return@Runnable
        if (!run.isOpen() || proximityPolicy.sparseLearning) return@Runnable
        cadenceClassified = true
        continuousCadenceConfirmed = proximityCadenceWindowIsContinuous(proximitySampleCount)
        proximitySampleCount = 0
        if (continuousCadenceConfirmed) {
            cancelOnChangeProbe()
            scheduleStaleCheck(PROXIMITY_STALE_MS + 1L)
        } else {
            scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
            // A second true on-change edge was capped while this window was unresolved. Re-admitting
            // sparse reporting must flush that held numeric state even if the HAL now remains silent.
            proximityRuntime?.tick(
                SystemClock.elapsedRealtime(),
                sparseReporting = true,
            )?.let { deliverProximity(it, run) }
        }
    }

    private val staleCheck = Runnable {
        staleCheckScheduled = false
        val run = activeRun ?: return@Runnable
        if (!run.isOpen() || !continuousCadenceConfirmed || liveProximityAt <= 0L) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val remaining = PROXIMITY_STALE_MS - (now - liveProximityAt)
        if (remaining >= 0L) {
            scheduleStaleCheck(remaining + 1L)
            return@Runnable
        }
        proximityRuntime?.sourceUnavailable(now)?.let { deliverProximity(it, run) }
        val recovery = proximityCadenceAfterStale()
        cadenceClassified = recovery.cadenceClassified
        continuousCadenceConfirmed = recovery.continuousCadenceConfirmed
        proximitySampleCount = recovery.sampleCount
        // Healthy dense delivery is watched without perturbing the HAL. A real stall is different:
        // re-register immediately, then make the resumed callbacks prove their cadence again.
        scheduleOnChangeProbe(1L)
    }

    private val onChangeProbeTimeout = Runnable {
        if (!onChangeProbeAwaiting) return@Runnable
        onChangeProbeAwaiting = false
        val run = activeRun ?: return@Runnable
        if (!run.isOpen()) return@Runnable
        deliverUnavailable(run)
        scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
    }

    private val onChangeProbe = Runnable {
        onChangeProbeScheduled = false
        val run = activeRun ?: return@Runnable
        val sensor = proximitySensor ?: return@Runnable
        val activeListener = listener ?: return@Runnable
        val handler = sensorHandler ?: return@Runnable
        if (!run.isOpen() || !needsHalLivenessProbe()) return@Runnable
        // Android on-change registration is required to produce a current-value event. Re-registering
        // infrequently is a liveness probe, not a polling path; failure takes HA offline fail-closed.
        sm.unregisterListener(activeListener, sensor)
        onChangeProbeAwaiting = true
        val registered = sm.registerListener(activeListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        if (!registered) {
            onChangeProbeAwaiting = false
            deliverUnavailable(run)
            scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
        } else {
            handler.postDelayed(onChangeProbeTimeout, ON_CHANGE_ACQUIRE_TIMEOUT_MS)
        }
    }

    init {
        if (!hasProximity()) {
            // Retirement is unconditional: a panel that lost/replaced its source must not carry old
            // threshold heuristics into a later install.
            config.consumeLegacyProximityLearningSeed()
            proximityPrepared = true
        }
    }

    /** Construct fixed calibration after any predecessor service has closed its sensor store. */
    @Synchronized
    fun prepare() {
        if (proximityPrepared) return
        proximityPrepared = true
        proximityRuntime = ProximityCalibrationRuntime(
            appContext, proximitySourceIdentity(), "${profile.id}|${profile.revision}",
            profile.proximityCalibration.takeUnless { proximityAcquisition == ProximityAcquisition.STK_RAW },
            observedSourceMode = if (proximityAcquisition == ProximityAcquisition.STK_RAW) ProximityCalibrationEngine.Mode.RANGED else null,
        )
        updateLearnedEligibility()
    }

    fun hasLight() = lightSensor != null

    /** Whether the illuminance channel is described (true), settled absent (false) or not yet known
     *  (null): the single answer every advertising path reads. See [LightAvailabilityTracker.channel]. */
    fun lightChannel(): Boolean? = lightAvailability.channel()

    /** Notify the service only when the advertised light answer changes. Carries no truth: every
     *  consumer re-reads this reporter before acting, exactly as learned proximity does. */
    fun setLightAvailabilityListener(listener: (() -> Unit)?) {
        synchronized(this) { lightAvailabilityListener = listener }
        listener?.invoke()
    }
    fun hasProximity() = proximityAcquisition != ProximityAcquisition.ABSENT
    fun hasLearnedProximity() = proximityRuntime?.isLearnedSignal() == true

    /**
     * [hasLearnedProximity] once it is settled: null before [prepare] has loaded the calibration, when the stored
     * calibration could not be read, and after the runtime has closed, when a false would only mean "not known".
     */
    @Synchronized
    fun learnedProximityState(): Boolean? {
        if (!proximityPrepared) return null
        if (!hasProximity()) return false
        val runtime = proximityRuntime ?: return null
        return runtime.learnedSignalState()
    }
    fun hasTemperature() = environmentalSensorPublishes(tempUse)
    fun hasHumidity() = environmentalSensorPublishes(humidityUse)

    fun lightDesc(): String? = lightSensor?.let { "Float · 0–${fmtV(it.maximumRange)} lx" }

    fun proximityDesc(): String? = proximityRuntime?.summary()

    fun proximitySummary(): String = proximityRuntime?.summary() ?: "No proximity source"

    fun proximityReady(): Boolean = proximityRuntime?.isWaveReady() == true

    fun proximityPresenceReady(): Boolean = proximityRuntime?.isPresenceReady() == true

    fun proximityPresenceNear(): Boolean = proximityRuntime?.isPresenceNear() == true

    /** Notify the service only when empirical signal-shape eligibility changes. Notifications carry no
     *  truth: every active consumer re-reads this service-owned reporter before acting. */
    fun setLearnedProximityListener(listener: (() -> Unit)?) {
        val current = hasLearnedProximity()
        synchronized(this) {
            learnedProximityListener = listener
            lastLearnedEligibility = current
        }
        listener?.invoke()
    }

    /** Live readings for the panel UI. Raw proximity is diagnostic-only and never leaves this API. */
    fun roomClimateSnapshot(): RoomClimate? = roomClimateRefresh?.current()

    fun valuesJson(roomClimate: RoomClimate? = roomClimateSnapshot()): String {
        val now = SystemClock.elapsedRealtime()
        fun age(at: Long): Long? = if (at <= 0L) null else ((now - at).coerceAtLeast(0L) / 1000L)
        val light = if (!hasLight()) "\"present\":false" else
            "\"present\":true,\"lux\":${if (liveLux.isNaN()) "null" else fmtV(liveLux)},\"age_s\":${age(liveLuxAt) ?: "null"}"
        val proximity = proximityRuntime?.json(lastRaw, age(liveProximityAt)) ?: "{\"present\":false}"
        val temp = environmentalEndpointJson(
            tempUse, liveTemp, age(liveTempAt), roomClimate?.tempC, tempActivation?.state,
        ).replace("\"value\":", "\"c\":")
        val humidity = environmentalEndpointJson(
            humidityUse, liveHumid, age(liveHumidAt), roomClimate?.humidityPct, humidityActivation?.state,
        ).replace("\"value\":", "\"pct\":")
        return "\"light\":{$light},\"proximity\":$proximity,\"temperature\":{$temp},\"humidity\":{$humidity}"
    }

    fun proximityJson(): String = proximityRuntime?.json(
        lastRaw,
        if (liveProximityAt <= 0L) null else
            ((SystemClock.elapsedRealtime() - liveProximityAt).coerceAtLeast(0L) / 1000L),
    ) ?: "{\"present\":false,\"learning\":\"unavailable\",\"phase\":\"unavailable\"}"

    fun proximityGestureToken(): Long = proximityRuntime?.gestureToken() ?: -1L

    fun completeProximityGesture(token: Long, accepted: Boolean) { proximityRuntime?.completeGesture(token, accepted) }

    fun proximityGeneration(): Long = proximityRuntime?.generation() ?: -1L

    fun proximityCalibrationActive(): Boolean = proximityRuntime?.active() == true

    fun startProximityCalibration(): Boolean {
        if (!hasProximity() || proximityRuntime?.start() != true) return false
        probeCalibrationSource()
        return true
    }

    private fun probeCalibrationSource() {
        sensorHandler?.post {
            // A fresh current-value probe verifies an on-change source before the local user begins.
            // Its first callback may establish clear state, but must never actuate wake.
            if (proximityAcquisition == ProximityAcquisition.ANDROID_HAL) {
                val current = listener
                val sensor = proximitySensor
                if (current != null && sensor != null) {
                    cancelOnChangeProbe()
                    onChangeProbeAwaiting = true
                    sm.unregisterListener(current, sensor)
                    if (!sm.registerListener(current, sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)) {
                        activeRun?.let(::deliverUnavailable)
                    } else sensorHandler?.postDelayed(onChangeProbeTimeout, ON_CHANGE_ACQUIRE_TIMEOUT_MS)
                }
            }
            scheduleCalibrationTick()
        }
    }

    fun proximityCalibrationHeartbeat(id: String): Boolean = proximityRuntime?.heartbeat(id) == true

    fun proximityCalibrationVisible(): Boolean = proximityRuntime?.visible() == true

    fun proximityCalibrationAction(action: String): Boolean {
        val accepted = proximityRuntime?.localAction(action) == true
        if (accepted && action == "retry") probeCalibrationSource()
        sensorHandler?.post { scheduleCalibrationTick(); updateLearnedEligibility() }
        return accepted
    }

    fun cancelProximitySession(id: String? = null, message: String = ""): Boolean =
        proximityRuntime?.cancel(id, message) == true

    fun resetProximityToProfile(): Boolean {
        if (proximityRuntime?.resetToProfile() != true) return false
        updateLearnedEligibility()
        activeRun?.proximity(null, null)
        return true
    }

    @Synchronized
    fun start(
        onLux: (Int) -> Unit,
        onProximity: (Boolean?, Int?, Int) -> Unit,
        onGesture: () -> Unit = {},
        onTemperature: (Float) -> Unit = {},
        onHumidity: (Float) -> Unit = {},
        onLuxRaw: (Float) -> Unit = {},
        onPresenceApproach: () -> Unit = {},
    ) {
        if (!hasLight() && !hasProximity() && tempSensor == null && humiditySensor == null && !hasCht8305) return
        if (activeRun != null) return
        val run = SensorRunCallbacks(onLux, onLuxRaw, onProximity, onGesture, onTemperature, onHumidity, onPresenceApproach)
        activeRun = run
        proximitySampleCount = 0
        proximityReceived = false
        proximityTimestampGate.reset()
        cadenceClassified = proximityPolicy.sparseLearning
        continuousCadenceConfirmed = false
        cadenceCheckScheduled = false
        staleCheckScheduled = false
        onChangeProbeScheduled = false
        onChangeProbeAwaiting = false

        listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                if (!run.isOpen() || activeRun !== run || event.values.isEmpty()) return
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> {
                        val lux = event.values[0]
                        val now = SystemClock.elapsedRealtime()
                        liveLux = lux
                        liveLuxAt = now
                        lightAvailability.reading()
                        run.light(lux, now)
                    }
                    Sensor.TYPE_PROXIMITY -> if (proximityAcquisition != ProximityAcquisition.STK_RAW) {
                        handleProximity(event.values[0], run, event.timestamp)
                    }
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                        if (!environmentalSensorPublishes(tempUse)) return
                        val value = event.values[0]
                        val now = SystemClock.elapsedRealtime()
                        liveTemp = value
                        liveTempAt = now
                        run.temperature(value, now)
                    }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> {
                        if (!environmentalSensorPublishes(humidityUse)) return
                        val value = event.values[0]
                        val now = SystemClock.elapsedRealtime()
                        liveHumid = value
                        liveHumidAt = now
                        run.humidity(value, now)
                    }
                }
            }
        }

        val thread = HandlerThread("ha-paneld-sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler
        if (hasCht8305) {
            val executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "ha-paneld-room-climate").apply { isDaemon = true }
            }
            roomClimateExecutor = executor
            roomClimateRefresh = AsyncRoomClimateSnapshot(
                read = { roomClimateMetrics?.roomClimate() },
                elapsedRealtime = SystemClock::elapsedRealtime,
                schedule = { delayMs, refresh ->
                    val future = executor.schedule(refresh, delayMs, TimeUnit.MILLISECONDS)
                    RoomClimateRefreshCancellation { future.cancel(true) }
                },
            ).also { it.start() }
        }
        lightSensor?.let {
            // An em3071x declared by the device tree but absent from the bus refuses activation here
            // (HAL: "Error activating sensor"). Treat that exactly as a failed proximity registration.
            val registered = sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            if (!registered) Log.w(TAG, "light sensor registration refused: ${it.name} (${it.vendor})")
            lightAvailability.registered(registered)
        }
        if (hasProximity() && !initializeProximitySource(run, { activeRun === run }) {
                if (proximityAcquisition == ProximityAcquisition.VI530X) {
                    val client = Vi530xProximityClient(
                        onValue = { raw ->
                            handler.post {
                                if (run.isOpen() && activeRun === run) handleProximity(raw, run)
                            }
                        },
                        onUnavailable = {
                            handler.post {
                                if (run.isOpen() && activeRun === run) deliverUnavailable(run)
                            }
                        },
                    )
                    vi530xClient = client
                    client.start()
                } else if (proximityAcquisition == ProximityAcquisition.STK_RAW) {
                    // Registration activates the driver; its binary values are deliberately ignored.
                    val registered = proximitySensor?.let {
                        sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
                    } == true
                    if (registered) startStkRaw(run, handler) else deliverUnavailable(run)
                } else if (proximityAcquisition == ProximityAcquisition.ANDROID_HAL) {
                    proximitySensor?.let {
                        if (proximityPolicy.onChangeHalLiveness) onChangeProbeAwaiting = true
                        val registered = sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
                        if (proximityPolicy.onChangeHalLiveness) {
                            if (registered) {
                                // Registration happens on the service-start caller while delivery uses this
                                // handler. Arm from the handler so an already-delivered initial value can clear
                                // the awaiting flag before any timeout is scheduled.
                                handler.post {
                                    if (onChangeProbeAwaiting && activeRun === run) {
                                        handler.postDelayed(onChangeProbeTimeout, ON_CHANGE_ACQUIRE_TIMEOUT_MS)
                                    }
                                }
                            } else {
                                onChangeProbeAwaiting = false
                                scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
                            }
                        }
                    }
                } else if (proximityAcquisition == ProximityAcquisition.GPIO) {
                    val client = GpioProximityClient(
                        gpio = checkNotNull(proximityGpio),
                        onValue = { raw -> if (run.isOpen() && activeRun === run) handleProximity(raw, run) },
                        onUnavailable = {
                            if (run.isOpen() && activeRun === run) {
                                deliverUnavailable(run)
                            }
                        },
                    )
                    gpioClient = client
                    client.start()
                }
            }
        ) return
        tempActivation = registerEnvironmentalSensor(tempSensor, tempUse, "temperature", run, handler)
        humidityActivation = registerEnvironmentalSensor(humiditySensor, humidityUse, "humidity", run, handler)
        Log.i(
            TAG,
            "sensors started (light=${lightAvailability.label()} proximity=${hasProximity()} " +
                "temp=$tempUse humidity=$humidityUse)",
        )
    }

    private fun startStkRaw(run: SensorRunCallbacks, handler: Handler) {
        val gate = StkRawProximityGate()
        stkRawGate = gate
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "ha-paneld-stk-raw").apply { isDaemon = true }
        }
        stkRawExecutor = executor
        // A blocked sysfs read must not retain readiness. This handler watchdog is independent of
        // both read completion and whether the calibration runtime currently needs gesture ticks.
        val watchdog = object : Runnable {
            override fun run() {
                if (!run.isOpen() || activeRun !== run) return
                if (gate.expire(SystemClock.elapsedRealtime())) deliverUnavailable(run)
                handler.postDelayed(this, 100L)
            }
        }
        stkRawWatchdog = watchdog
        handler.postDelayed(watchdog, 100L)
        // Schedule the next read only after delivery: at most one pending result, including when
        // the sensor handler is delayed. All filesystem work stays off that handler.
        fun scheduleRead() {
            if (!run.isOpen() || activeRun !== run || executor.isShutdown) return
            runCatching {
                executor.schedule({
                    val readStartedAt = SystemClock.elapsedRealtime()
                    val raw = runCatching { stkRawReader.read() }.getOrNull()
                    handler.post {
                        if (!run.isOpen() || activeRun !== run) return@post
                        val now = SystemClock.elapsedRealtime()
                        val admitted = stkRawReadWithinDeadline(raw, readStartedAt, now)
                        val sample = gate.observe(admitted, now) ?: return@post
                        if (sample.becameUnavailable || sample.raw == null) deliverUnavailable(run)
                        sample.raw?.let { value ->
                            lastRaw = value.toFloat()
                            // Diagnostic age follows real cache changes, not repeated reads.
                            if (sample.fresh && lastStkRaw != value) liveProximityAt = now
                            lastStkRaw = value
                            if (!sample.fresh) {
                                deliverUnavailable(run)
                            } else proximityRuntime?.observe(
                                value.toFloat(), now, sparseReporting = false,
                                live = sample.wakeEligible, calibrationLive = sample.fresh,
                            )?.let { deliverProximity(it, run) }
                            updateLearnedEligibility()
                            scheduleCalibrationTick()
                        }
                        scheduleRead()
                    }
                }, 100L, TimeUnit.MILLISECONDS)
            }
        }
        lastStkRaw = null
        scheduleRead()
    }

    private var lastStkRaw: Int? = null

    private fun registerEnvironmentalSensor(
        sensor: Sensor?,
        use: EnvironmentalSensorUse,
        label: String,
        run: SensorRunCallbacks,
        handler: Handler,
    ): ActivationRegistrationLifecycle? {
        sensor ?: return null
        if (environmentalSensorPublishes(use)) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            return null
        }
        if (use != EnvironmentalSensorUse.ACTIVATE_ONLY) return null
        return ActivationRegistrationLifecycle(
            register = {
                val activeListener = listener
                run.isOpen() && activeRun === run && activeListener != null &&
                    sm.registerListener(activeListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            },
            schedule = { delayMs, retry ->
                val task = Runnable(retry)
                handler.postDelayed(task, delayMs)
                ActivationRetryCancellation { handler.removeCallbacks(task) }
            },
            onState = { state -> Log.i(TAG, "$label activation registration=${state.name.lowercase(Locale.ROOT)}") },
        ).also { it.start() }
    }

    private fun handleProximity(raw: Float, run: SensorRunCallbacks, timestampNs: Long? = null) {
        if (!run.isOpen() || activeRun !== run) return
        val now = SystemClock.elapsedRealtime()
        val fresh = timestampNs == null || proximityTimestampGate.accept(timestampNs, now, System.currentTimeMillis())
        val wakeEligible = fresh && proximityReceived && !onChangeProbeAwaiting
        proximityReceived = true
        // Preserve the admitted final edge, then immediately reopen empirical classification. A HAL
        // that resumes dense delivery after a quiet startup therefore gets at most that one sparse
        // report before returning to the numeric rate budget.
        val sparseForObservation = reportingSparse()
        lastRaw = raw
        if (raw.isFinite() && fresh) {
            liveProximityAt = now
            if (!proximityPolicy.sparseLearning && !continuousCadenceConfirmed) {
                if (cadenceClassified) {
                    cadenceClassified = false
                    proximitySampleCount = 1
                } else {
                    proximitySampleCount++
                }
                if (!cadenceCheckScheduled) {
                    cadenceCheckScheduled = true
                    sensorHandler?.postDelayed(cadenceCheck, CADENCE_CLASSIFY_MS)
                }
            } else if (continuousCadenceConfirmed && !staleCheckScheduled) {
                // A valid sample after a previously detected stall starts a fresh watchdog epoch.
                scheduleStaleCheck(PROXIMITY_STALE_MS + 1L)
            }
            if (onChangeProbeAwaiting) {
                sensorHandler?.removeCallbacks(onChangeProbeTimeout)
                onChangeProbeAwaiting = false
            }
            if (needsHalLivenessProbe()) {
                scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
            }
        }
        val decision = proximityRuntime?.observe(raw, now, sparseReporting = sparseForObservation, live = wakeEligible, calibrationLive = fresh) ?: return
        updateLearnedEligibility()
        deliverProximity(decision, run)
        scheduleCalibrationTick()
    }

    private fun deliverProximity(decision: ProximityCalibrationRuntime.Decision, run: SensorRunCallbacks) {
        SensorTrace.recordProx(lastRaw, decision.near)
        if (decision.reportMask != ProximityReportGate.NONE) {
            run.proximity(decision.near, decision.normalizedLevel, decision.reportMask)
        }
        if (decision.presenceApproach) run.presenceApproach()
        if (decision.deliberateGesture) run.gesture()
    }

    private fun scheduleStaleCheck(delayMs: Long) {
        if (staleCheckScheduled) return
        val handler = sensorHandler ?: return
        staleCheckScheduled = true
        handler.postDelayed(staleCheck, delayMs.coerceAtLeast(1L))
    }

    private fun scheduleOnChangeProbe(delayMs: Long) {
        if (onChangeProbeScheduled || onChangeProbeAwaiting) return
        val handler = sensorHandler ?: return
        onChangeProbeScheduled = true
        handler.postDelayed(onChangeProbe, delayMs.coerceAtLeast(1L))
    }

    private fun cancelOnChangeProbe() {
        sensorHandler?.removeCallbacks(onChangeProbe)
        sensorHandler?.removeCallbacks(onChangeProbeTimeout)
        onChangeProbeScheduled = false
        onChangeProbeAwaiting = false
    }

    private fun needsHalLivenessProbe(): Boolean = proximityNeedsHalLivenessProbe(
        acquisition = proximityAcquisition,
        onChangeHalLiveness = proximityPolicy.onChangeHalLiveness,
        cadenceClassified = cadenceClassified,
        continuousCadenceConfirmed = continuousCadenceConfirmed,
    )

    private fun reportingSparse(): Boolean = proximityReportingSparse(
        sparseLearning = proximityPolicy.sparseLearning,
        cadenceClassified = cadenceClassified,
        continuousCadenceConfirmed = continuousCadenceConfirmed,
    )

    private fun deliverUnavailable(run: SensorRunCallbacks) {
        proximityRuntime?.sourceUnavailable()?.takeIf {
            it.reportMask != ProximityReportGate.NONE
        }?.let {
            run.proximity(null, null, it.reportMask)
        }
    }

    private fun notifyLightAvailability() {
        synchronized(this) { lightAvailabilityListener }?.invoke()
    }

    private fun updateLearnedEligibility() {
        val current = hasLearnedProximity()
        val listener = synchronized(this) {
            if (current == lastLearnedEligibility) return
            lastLearnedEligibility = current
            learnedProximityListener
        }
        listener?.invoke()
    }

    private fun proximitySourceIdentity(): String {
        val acquisition = when (proximityAcquisition) {
            ProximityAcquisition.STK_RAW -> StkRawProximityReader.SOURCE_IDENTITY
            ProximityAcquisition.VI530X -> "helper-vi530x"
            ProximityAcquisition.GPIO -> "helper-gpio:${checkNotNull(proximityGpio)}"
            ProximityAcquisition.ANDROID_HAL -> proximitySensor?.let { sensor ->
                listOf(
                    "android-hal",
                    sensor.vendor,
                    sensor.name,
                    sensor.stringType,
                    sensor.version.toString(),
                    sensor.type.toString(),
                    sensor.resolution.toString(),
                    sensor.maximumRange.toString(),
                ).joinToString("|")
            } ?: "absent"
            ProximityAcquisition.ABSENT -> "absent"
        }
        // Firmware is part of the epoch, not a behavior rule. A vendor can invert or quantize the same
        // named HAL sensor in an OTA; selecting a new empty model is safer than validating old anchors
        // against an on-change stream whose new far value may look exactly like the old near value.
        return "$acquisition|build=${Build.FINGERPRINT}|display=${Build.DISPLAY}"
    }

    private fun fmtV(value: Float) = formatSensorValue(value)

    @Synchronized
    fun stop(): CompletableFuture<Unit> {
        activeRun?.close()
        activeRun = null
        stkRawWatchdog?.let { sensorHandler?.removeCallbacks(it) }
        stkRawWatchdog = null
        stkRawGate?.close()
        stkRawGate = null
        stkRawExecutor?.shutdownNow()
        stkRawExecutor = null
        roomClimateRefresh?.stop()
        roomClimateRefresh = null
        roomClimateExecutor?.shutdownNow()
        roomClimateExecutor = null
        lightAvailability.stop()
        tempActivation?.stop()
        tempActivation = null
        humidityActivation?.stop()
        humidityActivation = null
        gpioClient?.stop()
        gpioClient = null
        vi530xClient?.stop()
        vi530xClient = null
        listener?.let { sm.unregisterListener(it) }
        listener = null
        sensorHandler?.removeCallbacks(calibrationTick)
        calibrationTickScheduled = false
        sensorHandler?.removeCallbacks(cadenceCheck)
        sensorHandler?.removeCallbacks(staleCheck)
        sensorHandler?.removeCallbacks(onChangeProbe)
        sensorHandler?.removeCallbacks(onChangeProbeTimeout)
        sensorHandler = null
        sensorThread?.quitSafely()
        sensorThread = null
        return proximityRuntime?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
    }

    private companion object {
        const val TAG = "ha-paneld/sensors"
        const val CADENCE_CLASSIFY_MS = 3_000L
        const val PROXIMITY_STALE_MS = 60_000L
        const val ON_CHANGE_PROBE_INTERVAL_MS = 15L * 60_000L
        const val ON_CHANGE_ACQUIRE_TIMEOUT_MS = 5_000L
    }
}

/** Some HALs mix a monotonic registration seed with wall-clock live edges in the same stream.
 * Accept only samples contemporary with one unambiguous clock, then order both on elapsed time.
 * Rejected observations never advance either timestamp highwater. Acquisition still decides whether
 * an otherwise fresh registration/probe sample is allowed to actuate a wake.
 */
internal class ProximityTimestampGate {
    private var elapsedTimestampNs = 0L
    private var wallTimestampNs = 0L
    private var normalizedSampleMs: Long? = null
    private var lastReceivedElapsed: Long? = null
    private var lastClockOffset: Long? = null
    private var wallUncertainUntil = 0L

    fun reset() {
        elapsedTimestampNs = 0L
        wallTimestampNs = 0L
        normalizedSampleMs = null
        lastReceivedElapsed = null
        lastClockOffset = null
        wallUncertainUntil = 0L
    }

    fun accept(timestampNs: Long, receivedElapsedMs: Long, receivedWallMs: Long): Boolean {
        if (timestampNs <= 0L || receivedElapsedMs < 0L || receivedWallMs < 0L) return false
        if (lastReceivedElapsed?.let { receivedElapsedMs < it } == true) return false
        lastReceivedElapsed = receivedElapsedMs
        val offset = receivedWallMs - receivedElapsedMs
        val previousOffset = lastClockOffset
        lastClockOffset = offset
        if (previousOffset != null && kotlin.math.abs(offset.toDouble() - previousOffset.toDouble()) > CLOCK_PAIR_TOLERANCE_MS) {
            wallUncertainUntil = receivedElapsedMs + FRESHNESS_MS
            // Invalidate any in-flight wave at the first sign of a wall-clock discontinuity.
            return false
        }
        val sampleMs = timestampNs / 1_000_000L
        val elapsedContemporary = contemporary(sampleMs, receivedElapsedMs)
        val wallContemporary = contemporary(sampleMs, receivedWallMs)
        // A timestamp fitting both clocks is ambiguous; fitting neither is stale or malformed.
        if (elapsedContemporary == wallContemporary) return false
        val normalized: Long
        if (elapsedContemporary) {
            if (timestampNs <= elapsedTimestampNs) return false
            normalized = sampleMs
        } else {
            if (receivedElapsedMs < wallUncertainUntil || timestampNs <= wallTimestampNs) return false
            normalized = receivedElapsedMs - (receivedWallMs - sampleMs)
            if (normalized < 0L) return false
        }
        if (normalizedSampleMs?.let { normalized <= it } == true) return false
        if (elapsedContemporary) elapsedTimestampNs = timestampNs else wallTimestampNs = timestampNs
        normalizedSampleMs = normalized
        return true
    }

    private fun contemporary(sampleMs: Long, receivedMs: Long): Boolean =
        sampleMs <= receivedMs && receivedMs - sampleMs <= FRESHNESS_MS

    companion object {
        private const val FRESHNESS_MS = 750L
        // The clocks are read consecutively; tolerate their millisecond quantization, not clock steps.
        private const val CLOCK_PAIR_TOLERANCE_MS = 5.0
    }
}
