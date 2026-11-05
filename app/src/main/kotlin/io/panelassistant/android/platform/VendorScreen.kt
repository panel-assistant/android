package io.panelassistant.android.platform

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A vendor process that switches the backlight while Android stays awake, for the
 * [io.panelassistant.android.device.ScreenOff.VENDOR_RPC] route. Seamed so
 * [io.panelassistant.android.control.ScreenController] runs on the JVM.
 */
interface VendorScreen {
    /** Ask the vendor to switch the backlight. False when it did not answer or refused. */
    fun set(on: Boolean): Boolean

    /** The backlight node's level; null when the node cannot be read. */
    fun level(): Int?

    /** Whether the backlight node reads above zero; null when the node cannot be read. */
    fun lit(): Boolean? = level()?.let { it > 0 }

    /** Stop the vendor driving the backlight from its own light sensor, so the app's level is the only
     *  one. Idempotent; false when the vendor did not answer. */
    fun holdBrightness(): Boolean
}

/** No vendor route: never answers and never reads, so the route falls back. */
object NoVendorScreen : VendorScreen {
    override fun set(on: Boolean) = false
    override fun level(): Int? = null
    override fun holdBrightness() = false
}

/**
 * Shelly's Stargate: its unauthenticated Gen2 RPC `Ui.Screen.Set` on loopback, at the port the profile
 * declares for its local service (`platform.launcher.vendor_service.port`). Stargate is a system app, so
 * it writes the backlight node the app cannot; it writes it behind Android's back, so `SCREEN_BRIGHTNESS`
 * is unchanged and only the node shows the result. Blocking; never call it on the main thread.
 */
interface VendorBrightnessCustody {
    fun read(): String?
    fun write(value: String): Boolean
    val freshInstall: Boolean
}

class LocalRpcVendorScreen(
    private val port: Int,
    private val custody: VendorBrightnessCustody,
) : VendorScreen {
    override fun set(on: Boolean): Boolean = call("Ui.Screen.Set?on=$on")

    // Serialize both instances (service and removal), including the RPC and durable fence.
    override fun holdBrightness(): Boolean = synchronized(CUSTODY_LOCK) {
        var state = custody.read()
        if (state == null) {
            state = if (custody.freshInstall) "pending" else "unknown"
            if (!custody.write(state)) return@synchronized false
        }
        if (state == "pending") {
            val original = autoBrightness() ?: return@synchronized false
            state = "active:$original"
            if (!custody.write(state)) return@synchronized false
        }
        if (state != "unknown" && state !in listOf("active:true", "active:false")) {
            return@synchronized false
        }
        setAutoBrightness(false)
    }

    /** Fence runtime writes before restoring. A missing historical baseline is never guessed. */
    fun restoreBrightness(): String = synchronized(CUSTODY_LOCK) {
        var state = custody.read()
        if (state == null || state == "pending") {
            // No takeover with this version occurred. Existing versions may have changed it already.
            state = if (state == "pending" || custody.freshInstall) "untouched" else "unknown"
        }
        if (state == "untouched" || state == "unknown" || state == "restoring:unknown" || state == "restored:untouched") {
            val result = if (state == "untouched" || state == "restored:untouched") "untouched" else "unknown"
            if (!custody.write(if (result == "untouched") "restored:untouched" else "restoring:unknown")) {
                return@synchronized "unrestored"
            }
            return@synchronized result
        }
        val original = when (state) {
            "active:true", "restoring:true", "restored:true" -> true
            "active:false", "restoring:false", "restored:false" -> false
            else -> return@synchronized "unrestored"
        }
        if (!custody.write("restoring:$original")) return@synchronized "unrestored"
        if (setAutoBrightness(original) && autoBrightness() == original && custody.write("restored:$original")) {
            "restored"
        } else "unrestored"
    }

    private fun autoBrightness(): Boolean? = runCatching {
        JSONObject(request("Ui.GetConfig") ?: return null)
            .getJSONObject("brightness").get("auto") as? Boolean
    }.getOrNull()

    private fun setAutoBrightness(auto: Boolean): Boolean =
        call("Ui.SetConfig?config=" + URLEncoder.encode("""{"brightness":{"auto":$auto}}""", "UTF-8"))

    private fun call(method: String): Boolean = request(method) != null

    private fun request(method: String): String? = runCatching {
        val connection = URL("http://127.0.0.1:$port/rpc/$method").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != 200) null else {
                connection.inputStream.bufferedReader().use { it.readText() }
                    .takeUnless { JSONObject(it).has("code") }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    override fun level(): Int? = runCatching {
        File(BACKLIGHT_CLASS).listFiles()?.sortedBy { it.name }?.firstNotNullOfOrNull { dir ->
            runCatching { File(dir, "actual_brightness").readText().trim().toInt() }.getOrNull()
        }
    }.getOrNull()

    internal companion object {
        val CUSTODY_LOCK = Any()
        const val BACKLIGHT_CLASS = "/sys/class/backlight"
        const val TIMEOUT_MS = 2_000
    }
}
