package io.panelassistant.android.control

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import io.panelassistant.android.util.Cached

internal data class WifiDiagnosticSnapshot(
    val ssid: String? = null,
    val rssiDbm: Int? = null,
    /** True only when Wi-Fi is the Android default network, not merely connected in the background. */
    val active: Boolean = false,
)

internal data class WifiDiagnosticDemand(
    val ssid: Boolean = false,
    val rssi: Boolean = false,
    /** True only when the caller's current capability observation already admitted direct su. */
    val privilegedRoute: Boolean = false,
)

internal fun needsPrivilegedWifiStatus(
    direct: WifiDiagnosticSnapshot,
    demand: WifiDiagnosticDemand,
): Boolean = demand.privilegedRoute &&
    direct.active &&
    (demand.ssid && direct.ssid == null || demand.rssi && direct.rssiDbm == null)

internal fun normalizedWifiSsid(raw: String?): String? {
    val value = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
    return value.takeIf {
        it.isNotEmpty() &&
            !it.equals("<unknown ssid>", ignoreCase = true) &&
            !it.equals("unknown ssid", ignoreCase = true)
    }
}

internal fun normalizedWifiRssi(raw: Int): Int? = raw.takeIf { it in -126..0 }

internal fun observedWifiSnapshot(
    activeWifi: Boolean,
    rawSsid: String?,
    rawRssiDbm: Int?,
): WifiDiagnosticSnapshot = if (!activeWifi) {
    WifiDiagnosticSnapshot()
} else {
    WifiDiagnosticSnapshot(
        ssid = normalizedWifiSsid(rawSsid),
        rssiDbm = rawRssiDbm?.let(::normalizedWifiRssi),
        active = true,
    )
}

internal data class WifiDiagnosticAvailability(
    val ssid: Boolean,
    val rssi: Boolean,
)

internal fun WifiDiagnosticSnapshot.availability(): WifiDiagnosticAvailability =
    WifiDiagnosticAvailability(
        ssid = active && ssid != null,
        rssi = active && rssiDbm != null,
    )

internal data class WifiDiagnosticAdmission(
    val active: Boolean,
    val ssid: Boolean,
    val rssi: Boolean,
)

internal fun WifiDiagnosticSnapshot.admission(): WifiDiagnosticAdmission {
    val available = availability()
    return WifiDiagnosticAdmission(active = active, ssid = available.ssid, rssi = available.rssi)
}

/** Suppresses duplicate callbacks while admitting same-transport diagnostic availability changes. */
internal class WifiDiagnosticAdmissionTracker {
    private var previous: WifiDiagnosticAdmission? = null

    @Synchronized
    fun changed(current: WifiDiagnosticAdmission): Boolean {
        if (previous == current) return false
        previous = current
        return true
    }
}

internal class WifiDiagnosticCache(
    nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val directCache = Cached(CACHE_MS, nowMs) { WifiDiagnosticSnapshot() }
    private val shellCache = Cached(CACHE_MS, nowMs) { WifiDiagnosticSnapshot() }

    fun snapshot(
        demand: WifiDiagnosticDemand,
        directReader: () -> WifiDiagnosticSnapshot,
        privilegedReader: () -> WifiDiagnosticSnapshot,
    ): WifiDiagnosticSnapshot {
        val direct = directCache.getWithSupplier {
            runCatching(directReader).getOrDefault(WifiDiagnosticSnapshot())
        }
        val shell = if (needsPrivilegedWifiStatus(direct, demand)) {
            shellCache.getWithSupplier {
                runCatching(privilegedReader).getOrDefault(WifiDiagnosticSnapshot())
            }
        } else {
            WifiDiagnosticSnapshot()
        }
        return WifiDiagnosticSnapshot(
            ssid = if (demand.ssid) direct.ssid ?: shell.ssid else null,
            rssiDbm = if (demand.rssi) direct.rssiDbm ?: shell.rssiDbm else null,
            active = direct.active,
        )
    }

    fun invalidate() {
        directCache.invalidate()
        shellCache.invalidate()
    }

    private companion object {
        const val CACHE_MS = 15_000L
    }
}

internal fun parseWifiShellSnapshot(raw: String?): WifiDiagnosticSnapshot {
    if (raw.isNullOrBlank()) return WifiDiagnosticSnapshot()
    val connectedSsid = Regex("""(?im)Wi-?Fi\s+is\s+connected\s+to\s+"([^"]+)"""")
        .find(raw)?.groupValues?.getOrNull(1)
    val wifiInfoSsid = Regex("""(?im)\bSSID:\s*(.*?),\s*BSSID:""")
        .find(raw)?.groupValues?.getOrNull(1)
    val rssi = Regex("""(?im)\bRSSI\s*[:=]\s*(-?\d+)""")
        .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return WifiDiagnosticSnapshot(
        ssid = normalizedWifiSsid(connectedSsid ?: wifiInfoSsid),
        rssiDbm = rssi?.let(::normalizedWifiRssi),
    )
}

/** Reads only the current Wi-Fi connection; it never scans or collects BSSID/MAC identifiers. */
internal class AndroidWifiDiagnostics(
    context: Context,
    private val privilegedStatus: () -> String? = { null },
) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val cache = WifiDiagnosticCache()

    @Suppress("DEPRECATION")
    private fun snapshotFor(capabilities: NetworkCapabilities?): WifiDiagnosticSnapshot {
        capabilities ?: return WifiDiagnosticSnapshot()
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiDiagnosticSnapshot()
        }
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            capabilities.transportInfo as? WifiInfo
        } else {
            wifi?.connectionInfo
        }
        return observedWifiSnapshot(
            activeWifi = true,
            rawSsid = info?.ssid,
            rawRssiDbm = info?.rssi,
        )
    }

    private fun currentSnapshot(): WifiDiagnosticSnapshot {
        val manager = connectivity ?: return WifiDiagnosticSnapshot()
        return snapshotFor(manager.activeNetwork?.let(manager::getNetworkCapabilities))
    }

    fun admission(capabilities: NetworkCapabilities?): WifiDiagnosticAdmission =
        snapshotFor(capabilities).admission()

    fun snapshot(demand: WifiDiagnosticDemand): WifiDiagnosticSnapshot = cache.snapshot(
        demand = demand,
        directReader = ::currentSnapshot,
        privilegedReader = {
            parseWifiShellSnapshot(runCatching(privilegedStatus).getOrNull())
        },
    )

    fun invalidate() = cache.invalidate()
}
