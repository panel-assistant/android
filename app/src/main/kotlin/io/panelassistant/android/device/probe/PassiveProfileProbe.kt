package io.panelassistant.android.device.probe

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.PassiveProfileConfidence
import io.panelassistant.android.device.profile.PassiveProfileObservation
import io.panelassistant.android.device.profile.PassiveProfileReport
import io.panelassistant.android.util.SystemProps
import java.io.File

/** App-UID-only, read-only facts used to seed a Generic panel profile. */
data class PassiveProbeSnapshot(
    val generatedAtEpochMs: Long,
    val facts: DeviceFacts,
    val androidSdk: Int,
    val abis: List<String>,
    val board: String,
    val hardware: String,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val sensors: Set<PassiveSensor>,
    val ledJniReadable: Boolean,
    val sysfsRgbReadable: Boolean,
    val cpuGovernors: List<String>,
)

enum class PassiveSensor(val schemaPath: String) {
    LIGHT("sensors.light_technology"),
    PROXIMITY("sensors.proximity_technology"),
    TEMPERATURE("evidence.sensors.temperature"),
    HUMIDITY("evidence.sensors.humidity"),
}

object PassiveProfileReportFactory {
    fun create(snapshot: PassiveProbeSnapshot): PassiveProfileReport {
        val observations = buildList {
            add(observed("match.any[].all[].field = model", snapshot.facts.model, "android.os.Build.MODEL"))
            add(observed("match.any[].all[].field = device", snapshot.facts.device, "android.os.Build.DEVICE"))
            add(observed("match.any[].all[].field = product_version", snapshot.facts.productVersion, "ro.product.version"))
            add(observed("evidence.android_sdk", snapshot.androidSdk.toString(), "android.os.Build.VERSION.SDK_INT"))
            add(observed("evidence.abis", snapshot.abis.joinToString(","), "android.os.Build.SUPPORTED_ABIS"))
            add(observed("evidence.board", snapshot.board, "android.os.Build.BOARD"))
            add(observed("evidence.hardware", snapshot.hardware, "android.os.Build.HARDWARE"))
            add(observed("evidence.display.width_px", snapshot.widthPx.toString(), "Resources.displayMetrics"))
            add(observed("evidence.display.height_px", snapshot.heightPx.toString(), "Resources.displayMetrics"))
            add(observed("evidence.display.current_density_dpi", snapshot.densityDpi.toString(), "Resources.displayMetrics"))
            PassiveSensor.entries.forEach { sensor ->
                add(observed(sensor.schemaPath, (sensor in snapshot.sensors).toString(), "Android SensorManager"))
            }
            add(observed("evidence.led.dev_ledjni_readable", snapshot.ledJniReadable.toString(), "app-UID path check"))
            add(observed("evidence.led.sysfs_rgb_readable", snapshot.sysfsRgbReadable.toString(), "app-UID path check"))
            add(observed("evidence.cpu.available_governors", snapshot.cpuGovernors.joinToString(","), "app-UID sysfs read"))
        }
        return PassiveProfileReport(
            generatedAtEpochMs = snapshot.generatedAtEpochMs,
            facts = snapshot.facts,
            observations = observations,
        )
    }

    private fun observed(path: String, value: String, source: String) = PassiveProfileObservation(
        path = path,
        value = value,
        source = source,
        confidence = PassiveProfileConfidence.OBSERVED,
    )
}

/**
 * Deliberately does not call su, the helper, Shizuku, Accessibility, a shell, or any write API.
 * Missing/unreadable evidence stays absent rather than triggering an active characterization probe.
 */
class AndroidPassiveProfileProbe(private val context: Context) {
    fun report(nowEpochMs: Long = System.currentTimeMillis()): PassiveProfileReport =
        PassiveProfileReportFactory.create(snapshot(nowEpochMs))

    internal fun snapshot(nowEpochMs: Long): PassiveProbeSnapshot {
        val metrics = context.resources.displayMetrics
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensors = buildSet {
            if (manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null) add(PassiveSensor.LIGHT)
            if (manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null) add(PassiveSensor.PROXIMITY)
            if (manager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE) != null) add(PassiveSensor.TEMPERATURE)
            if (manager?.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY) != null) add(PassiveSensor.HUMIDITY)
        }
        val governors = readText(File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"))
            ?.split(Regex("\\s+"))
            .orEmpty()
            .filter { it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) }
            .distinct()
            .take(MAX_GOVERNORS)
        val rgbNodes = listOf("red", "green", "blue").map { File("/sys/class/leds/$it") }
        return PassiveProbeSnapshot(
            generatedAtEpochMs = nowEpochMs,
            facts = DeviceFacts(Build.MODEL.orEmpty(), Build.DEVICE.orEmpty(), SystemProps.get("ro.product.version")),
            androidSdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.orEmpty().map(::clean).filter(String::isNotBlank).take(MAX_ABIS),
            board = clean(Build.BOARD.orEmpty()),
            hardware = clean(Build.HARDWARE.orEmpty()),
            widthPx = metrics.widthPixels.coerceAtLeast(0),
            heightPx = metrics.heightPixels.coerceAtLeast(0),
            densityDpi = metrics.densityDpi.coerceAtLeast(0),
            sensors = sensors,
            ledJniReadable = File("/dev/ledjni").canRead(),
            sysfsRgbReadable = rgbNodes.all(File::canRead),
            cpuGovernors = governors,
        )
    }

    private fun readText(file: File): String? = runCatching {
        if (!file.isFile || !file.canRead() || file.length() > MAX_SYSFS_BYTES) null
        else file.readText(Charsets.UTF_8).take(MAX_SYSFS_BYTES.toInt())
    }.getOrNull()

    private fun clean(value: String): String = value
        .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
        .trim()
        .take(MAX_TEXT)

    companion object {
        private const val MAX_SYSFS_BYTES = 4_096L
        private const val MAX_TEXT = 96
        private const val MAX_ABIS = 8
        private const val MAX_GOVERNORS = 16
    }
}
