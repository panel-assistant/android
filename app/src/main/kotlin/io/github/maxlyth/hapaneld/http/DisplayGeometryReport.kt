package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.maxlyth.hapaneld.device.profile.DisplayGeometryEvidence
import io.github.maxlyth.hapaneld.device.profile.ProfiledDisplayGeometry
import org.json.JSONObject
import kotlin.math.roundToLong

/** One read of the live display: the physical mode, what renders now, and Android's two logical densities. */
internal data class DisplayObservation(
    val physicalWidthPx: Int,
    val physicalHeightPx: Int,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    /** Android's factory reset logical DPI: the `wm density` reset reference ("Physical density") when a
     *  privileged read has it, otherwise the framework's stable density. */
    val factoryBaseDpi: Int?,
    val currentDpi: Int?,
)

/**
 * `GET /api/v1/display`: physical geometry and logical rendering density as separate facts. Physical
 * values come only from profile evidence resolved against the physical mode; logical DPI decides the dp
 * viewport and nothing else.
 */
internal object DisplayGeometryReport {
    fun observe(context: Context): DisplayObservation? = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val mode = display.mode
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        DisplayObservation(
            physicalWidthPx = mode.physicalWidth,
            physicalHeightPx = mode.physicalHeight,
            viewportWidthPx = metrics.widthPixels,
            viewportHeightPx = metrics.heightPixels,
            factoryBaseDpi = DisplayMetrics.DENSITY_DEVICE_STABLE.takeIf { it > 0 },
            currentDpi = metrics.densityDpi.takeIf { it > 0 },
        )
    } catch (e: Throwable) {
        null
    }

    fun json(observation: DisplayObservation, profiled: ProfiledDisplayGeometry?, recommendedDpi: Int?): JSONObject {
        val result = JSONObject()
            .put(
                "physical_pixels",
                JSONObject().put("width", observation.physicalWidthPx).put("height", observation.physicalHeightPx),
            )
        profiled?.physical?.let { physical ->
            result.put(
                "physical_size",
                JSONObject()
                    .put("diagonal_in", round(physical.diagonalInches, 2))
                    .put("width_mm", round(physical.widthMm, 1))
                    .put("height_mm", round(physical.heightMm, 1))
                    .put("ppi", round(physical.ppi, 1))
                    .put("evidence", physical.evidence.yamlName)
                    .put("approximate", physical.evidence == DisplayGeometryEvidence.APPROXIMATE)
                    .putOpt("variant", physical.variant)
                    .putOpt("evidence_note", physical.evidenceNote),
            )
        }
        result.put(
            "logical_dpi",
            JSONObject()
                .putOpt("factory_base", observation.factoryBaseDpi)
                .putOpt("profile_factory_base", profiled?.factoryBaseDpi)
                .putOpt("current", observation.currentDpi)
                .putOpt("recommended", recommendedDpi),
        )
        observation.currentDpi?.let { dpi ->
            val scale = dpi / DisplayMetrics.DENSITY_DEFAULT.toDouble()
            result.put(
                "viewport_dp",
                JSONObject()
                    .put("width", round(observation.viewportWidthPx / scale, 1))
                    .put("height", round(observation.viewportHeightPx / scale, 1))
                    .put("density_scale", round(scale, 4)),
            )
        }
        return result
    }

    private fun round(value: Double, places: Int): Double {
        var factor = 1.0
        repeat(places) { factor *= 10 }
        return (value * factor).roundToLong() / factor
    }
}
