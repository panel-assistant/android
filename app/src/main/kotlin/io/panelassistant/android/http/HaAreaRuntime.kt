package io.panelassistant.android.http

import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.config.isLiteralNullAreaName
import io.panelassistant.android.dashboard.HaAreaCatalog
import io.panelassistant.android.dashboard.applyRequestedArea
import io.panelassistant.android.dashboard.haAreaCatalog
import io.panelassistant.android.dashboard.haAreaOwnerKey
import io.panelassistant.android.haRefreshToken
import io.panelassistant.android.haToken
import io.panelassistant.android.haUrl
import io.panelassistant.android.util.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Share the area presentation for Configure and Setup while retaining raw rows for repair. */
internal fun haAreaCatalogPresentationJson(catalog: HaAreaCatalog): String {
    val areas = catalog.areas.filterNot { isLiteralNullAreaName(it.name) }.joinToString(",") { area ->
        "{\"area_id\":${Json.str(area.areaId)},\"name\":${Json.str(area.name)}," +
            "\"icon\":${Json.str(area.icon)}}"
    }
    val invalidDeviceArea = HaAreaProtocol.hasLiteralNullAssignment(catalog.device, catalog.areas)
    return "{\"areas\":[$areas],\"device\":{\"found\":${catalog.device.found}," +
        "\"area_id\":${Json.str(if (invalidDeviceArea) "" else catalog.device.areaId)}," +
        "\"area_name\":${Json.str(if (invalidDeviceArea) "" else catalog.device.areaName)}}," +
        "\"admin\":${catalog.admin},\"queried\":${catalog.queried}}"
}

internal class HaAreaRuntime(
    private val config: Config,
    private val scope: CoroutineScope,
    private val directConfigMutationLock: Any,
    private val onAreaAdopted: () -> Unit = {},
    private val stopping: () -> Boolean,
) {
    private val haAreaWarmLock = Any()
    private var haAreaJob: kotlinx.coroutines.Job? = null
    @Volatile private var haAreaWarmJob: kotlinx.coroutines.Job? = null
    private var haAreaWriteJob: kotlinx.coroutines.Job? = null

    suspend fun areaJson(): String {
        // Registry LISTS are readable by any authenticated HA user; `admin` tells the
        // pickers whether editing is honest to offer (moving a device is admin-only).
        val snapshot = captureHaAreaSnapshot()
        val catalog = applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot))
        return JSONObject(haAreaCatalogPresentationJson(catalog))
            .put("requested", config.haArea)
            .put("ha_username", catalog.haUsername)
            .toString()
    }

    fun stop() {
        haAreaJob?.cancel()
        haAreaJob = null
        synchronized(haAreaWarmLock) {
            haAreaWarmJob?.cancel()
            haAreaWarmJob = null
        }
        haAreaWriteJob?.cancel()
        haAreaWriteJob = null
    }

    fun onHaAreaCommitted() {
        haAreaWriteJob?.cancel()
        invalidateHaAreaCatalogCache()
        val snapshot = captureHaAreaSnapshot()
        haAreaWriteJob = scope.launch {
            runCatching {
                val before = haAreaCatalogFor(snapshot, fresh = true)
                applyHaAreaPrecedence(snapshot, before)
            }.onFailure { Log.w(TAG, "ha-area: write-back after config save failed", it) }
        }
    }

    /**
     * The single owner of the HA-canonical area rule: adopt what Home Assistant reports, or push a pending
     * request when HA has none and this session may write. Called by the area endpoint and by the
     * unprompted convergence loop below, so the rule has exactly one implementation.
     */
    private data class HaAreaSnapshot(
        val ownerKey: String,
        val deviceUid: String,
        val panelId: String,
        val localArea: String,
        val userOverride: Boolean = false,
    )

    private fun captureHaAreaSnapshot(): HaAreaSnapshot = HaAreaSnapshot(
        ownerKey = haAreaOwnerKey(config),
        deviceUid = config.deviceUid,
        panelId = config.panelId,
        localArea = config.haArea,
        userOverride = config.haAreaUserOverride,
    )

    /**
     * A briefly-held copy of the area registry, this device's row and the account's admin flag.
     *
     * All three change infrequently, yet the picker asked Home Assistant for them on EVERY
     * Configure paint: one authenticated WebSocket session per page load, and one per reload through an
     * upgrade round, which is what made the control look like it was constantly refreshing. Only successful
     * reads are held, so a failed query never becomes authoritative; the unprompted convergence pass
     * deliberately bypasses this, because noticing an admin's change in Home Assistant is its whole job.
     */
    private data class HaAreaCatalogCacheEntry(
        val key: String,
        val cachedAtMs: Long,
        val catalog: HaAreaCatalog,
    )

    @Volatile private var haAreaCatalogCache: HaAreaCatalogCacheEntry? = null

    private fun haAreaCatalogKey(snapshot: HaAreaSnapshot): String =
        "${snapshot.ownerKey}|${snapshot.deviceUid}|${snapshot.panelId}"

    private fun cacheHaAreaCatalog(
        snapshot: HaAreaSnapshot,
        catalog: HaAreaCatalog,
        nowMs: Long = System.nanoTime() / 1_000_000L,
    ) {
        synchronized(directConfigMutationLock) {
            if (catalog.queried && catalog.ownerKey == snapshot.ownerKey && ownsHaAreaSnapshot(snapshot)) {
                haAreaCatalogCache = HaAreaCatalogCacheEntry(haAreaCatalogKey(snapshot), nowMs, catalog)
            }
        }
    }

    private suspend fun haAreaCatalogFor(
        snapshot: HaAreaSnapshot,
        fresh: Boolean = false,
    ): HaAreaCatalog {
        val key = haAreaCatalogKey(snapshot)
        val now = System.nanoTime() / 1_000_000L
        if (!fresh) {
            haAreaCatalogCache?.takeIf { entry ->
                haAreaCacheEntryUsable(entry.key, key, entry.cachedAtMs, now, HA_AREA_CATALOG_TTL_MS)
            }?.catalog?.let { return it }
        }
        val catalog = haAreaCatalog(config)
        cacheHaAreaCatalog(snapshot, catalog, now)
        return catalog
    }

    /** A local area change must never be answered from a catalog read before it. */
    private fun invalidateHaAreaCatalogCache() {
        haAreaCatalogCache = null
    }

    /** Populate the config-response seed without making config rendering wait on Home Assistant. */
    private fun warmHaAreaCatalogInBackground() {
        synchronized(haAreaWarmLock) {
            if (stopping() || haAreaWarmJob?.isActive == true) return
            haAreaWarmJob = scope.launch {
                val credentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()
                if (!HaAreaProtocol.canQueryUnprompted(config.haUrl, credentialed)) return@launch
                runCatching { haAreaCatalogFor(captureHaAreaSnapshot(), fresh = true) }
                    .onFailure { Log.w(TAG, "ha-area: catalog warm failed", it) }
            }
        }
    }

    private fun ownsHaAreaSnapshot(snapshot: HaAreaSnapshot): Boolean =
        snapshot.ownerKey == haAreaOwnerKey(config) &&
            snapshot.deviceUid == config.deviceUid && snapshot.panelId == config.panelId &&
            snapshot.localArea == config.haArea && snapshot.userOverride == config.haAreaUserOverride

    private suspend fun applyHaAreaPrecedence(
        snapshot: HaAreaSnapshot,
        catalog: HaAreaCatalog,
        allowWriteBack: Boolean = true,
    ): HaAreaCatalog {
        if (!catalog.queried || !catalog.device.found || catalog.ownerKey != snapshot.ownerKey ||
            !ownsHaAreaSnapshot(snapshot)
        ) return catalog
        if (allowWriteBack && catalog.admin &&
            HaAreaProtocol.hasLiteralNullAssignment(catalog.device, catalog.areas) &&
            ownsHaAreaSnapshot(snapshot)
        ) {
            val cleared = applyRequestedArea(config, "", snapshot.ownerKey)
            if (cleared && ownsHaAreaSnapshot(snapshot)) {
                invalidateHaAreaCatalogCache()
                val after = haAreaCatalog(config)
                cacheHaAreaCatalog(snapshot, after)
                return applyHaAreaPrecedence(snapshot, after, allowWriteBack = false)
            }
        }
        when (HaAreaProtocol.reconcile(snapshot.localArea, catalog.device.areaName, catalog.admin, snapshot.userOverride)) {
            HaAreaProtocol.ReconcileAction.ADOPT_HA -> withContext(Dispatchers.IO) {
                val adopted = synchronized(directConfigMutationLock) {
                    if (!ownsHaAreaSnapshot(snapshot)) return@synchronized false
                    config.synchronizedTransaction {
                        if (!ownsHaAreaSnapshot(snapshot)) return@synchronizedTransaction false
                        Log.i(TAG, "ha-area: adopting Home Assistant's area for this device")
                        // Adoption is only reachable for a non-override value, or for an override that
                        // matches HA in a different casing — either way nothing local-only remains.
                        config.commitHaArea(catalog.device.areaName, userOverride = false)
                    }
                }
                if (adopted) onAreaAdopted()
            }
            HaAreaProtocol.ReconcileAction.WRITE_BACK -> if (allowWriteBack && ownsHaAreaSnapshot(snapshot)) {
                val moved = applyRequestedArea(config, snapshot.localArea, snapshot.ownerKey)
                if (moved && ownsHaAreaSnapshot(snapshot)) {
                    invalidateHaAreaCatalogCache()
                    val after = haAreaCatalog(config)
                    cacheHaAreaCatalog(snapshot, after)
                    return applyHaAreaPrecedence(snapshot, after, allowWriteBack = false)
                }
            }
            HaAreaProtocol.ReconcileAction.KEEP -> {
                // An override HA has come to agree with (exactly) is no longer overriding anything.
                if (snapshot.userOverride && catalog.device.areaName == snapshot.localArea &&
                    ownsHaAreaSnapshot(snapshot)
                ) withContext(Dispatchers.IO) {
                    synchronized(directConfigMutationLock) {
                        if (!ownsHaAreaSnapshot(snapshot)) return@synchronized
                        config.synchronizedTransaction {
                            if (!ownsHaAreaSnapshot(snapshot)) return@synchronizedTransaction false
                            config.commitHaArea(snapshot.localArea, userOverride = false)
                        }
                    }
                }
            }
        }
        return catalog
    }

    /**
     * Converge the panel's area WITHOUT waiting for a person.
     *
     * "Home Assistant is canonical" was implemented only at read time, and every reader was a UI control —
     * the Configure area picker and the wizard's dashboard step. A panel nobody had opened that dropdown on
     * therefore never adopted anything: affected panels held a blank `ha_area` while their HA
     * devices sat in real areas, so every surface honestly reported "No area". One unprompted
     * pass after start, then a slow repeat, is enough: the area of a wall panel changes about never, and the
     * read is one authenticated WebSocket round trip.
     */
    fun startHaAreaConvergence() {
        if (haAreaJob?.isActive == true) return
        warmHaAreaCatalogInBackground()
        haAreaJob = scope.launch {
            delay(HA_AREA_FIRST_PASS_MS)
            while (true) {
                val credentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()
                if (HaAreaProtocol.canQueryUnprompted(config.haUrl, credentialed)) {
                    runCatching { refreshFromHomeAssistant() }
                        .onFailure { Log.w(TAG, "ha-area: unprompted convergence failed", it) }
                }
                delay(HA_AREA_REPEAT_MS)
            }
        }
    }

    /**
     * One fresh registry read through the precedence rule. Panel Assistant asks for it when Home Assistant
     * moves this device, so the move does not wait for the slow pass. True only when Home Assistant answered.
     */
    suspend fun refreshFromHomeAssistant(): Boolean {
        val snapshot = captureHaAreaSnapshot()
        return applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot, fresh = true)).queried
    }

    /** Last successfully queried catalog for the current owner; never blocks a config response. */
    fun haAreaCatalogJson(): String? {
        val credentialed = config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()
        if (!HaAreaProtocol.canQueryUnprompted(config.haUrl, credentialed)) return null
        val snapshot = captureHaAreaSnapshot()
        val entry = haAreaCatalogCache
        val now = System.nanoTime() / 1_000_000L
        if (entry == null || !haAreaCacheEntryUsable(
                entry.key,
                haAreaCatalogKey(snapshot),
                entry.cachedAtMs,
                now,
                HA_AREA_CATALOG_TTL_MS,
            ) ||
            entry.catalog.ownerKey != snapshot.ownerKey || !entry.catalog.queried
        ) {
            warmHaAreaCatalogInBackground()
            return null
        }
        return haAreaCatalogPresentationJson(entry.catalog)
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        // Late enough that the first pass does not compete with boot (renderer, network runtime, profile activation),
        // early enough that a panel is correct long before anybody opens a settings page.
        // Long enough that a round of page reloads costs one Home Assistant read, short enough that an
        // admin who moves the device in HA sees it here without waiting for the six-hourly pass.
        private const val HA_AREA_CATALOG_TTL_MS = 10 * 60 * 1000L
        private const val HA_AREA_FIRST_PASS_MS = 45_000L
        private const val HA_AREA_REPEAT_MS = 6 * 60 * 60 * 1000L
    }
}
