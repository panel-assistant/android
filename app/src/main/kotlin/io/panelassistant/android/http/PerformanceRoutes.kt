package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal val PERFORMANCE_WORKLOAD_KEYS = listOf(
    "dashboard_package", "home_dashboard", "ha_url", "dashboard_fullscreen",
    "dashboard_native_kiosk", "dashboard_idle_return_min",
    "dashboard_zoom", "dark_mode", "dashboard_theme", "auto_brightness",
    "auto_brightness_minimum_percent", "auto_brightness_maximum_percent", "auto_brightness_response_percent",
    "auto_brightness_ha_entity", "cpu_governor", "keep_awake", "prevent_idle_dim",
)

internal val PERFORMANCE_COMPARISON_ID = Regex("^[0-9a-f]{32}$")
private val PERFORMANCE_DEVICE_SECRET = Regex("^[0-9a-f]{64}$")

internal fun validPerformanceDeviceSecret(value: String): Boolean =
    value.matches(PERFORMANCE_DEVICE_SECRET)

internal fun performanceBindingJson(
    comparisonId: String,
    deviceSecret: String,
    panelId: String,
    workload: Map<String, String>,
): String? {
    if (!comparisonId.matches(PERFORMANCE_COMPARISON_ID) || !validPerformanceDeviceSecret(deviceSecret)) return null
    if (workload.keys != PERFORMANCE_WORKLOAD_KEYS.toSet()) return null
    val key = SecretKeySpec(deviceSecret.lowercase().toByteArray(Charsets.UTF_8), "HmacSHA256")
    fun fingerprint(domain: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal("ha-paneld-perf/$domain\u0000$comparisonId\u0000$value".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    val workloadValue = buildString {
        workload.toSortedMap().forEach { (name, value) ->
            append(name.length).append(':').append(name)
            append(value.length).append(':').append(value)
        }
    }
    return JSONObject()
        .put("comparison_id", comparisonId)
        .put("panel_fingerprint", fingerprint("panel", panelId))
        .put("workload_fingerprint", fingerprint("workload", workloadValue))
        .toString()
}

internal fun Route.performanceRoutes(
    admit: suspend (ApplicationCall) -> Boolean,
    perf: () -> String,
    binding: (String) -> String?,
    costs: () -> String,
    history: (Int) -> String,
) {
    get("/perf") {
        if (!admit(call)) return@get
        call.respondText(perf(), ContentType.Application.Json)
    }
    get("/perf/binding") {
        if (!admit(call)) return@get
        val comparisonId = call.request.queryParameters["comparison_id"].orEmpty()
        if (!comparisonId.matches(PERFORMANCE_COMPARISON_ID)) return@get call.respondText(
            "{\"error\":\"invalid comparison_id\"}", ContentType.Application.Json, HttpStatusCode.BadRequest,
        )
        val result = binding(comparisonId) ?: return@get call.respondText(
            "{\"error\":\"stable device identity unavailable\"}",
            ContentType.Application.Json, HttpStatusCode.ServiceUnavailable,
        )
        call.respondText(result, ContentType.Application.Json)
    }
    // Sparse A/B harvesters must not activate the 2 s sampler.
    get("/perf/costs") {
        call.respondText(costs(), ContentType.Application.Json)
    }
    get("/perf/history") {
        if (!admit(call)) return@get
        val hours = call.request.queryParameters["hours"]?.toIntOrNull() ?: 24
        call.respondText(history(hours), ContentType.Application.Json)
    }
}
