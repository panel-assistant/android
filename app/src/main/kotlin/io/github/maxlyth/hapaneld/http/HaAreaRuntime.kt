package io.github.maxlyth.hapaneld.http

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.config.isLiteralNullAreaName
import io.github.maxlyth.hapaneld.dashboard.EntityLearningManager
import io.github.maxlyth.hapaneld.util.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Share the area presentation for Configure and Setup while retaining raw rows for repair. */
internal fun haAreaCatalogPresentationJson(catalog: EntityLearningManager.HaAreaCatalog): String {
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
    private val entityLearning: EntityLearningManager,
    private val scope: CoroutineScope,
    private val directConfigMutationLock: Any,
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
        ownerKey = entityLearning.haAreaOwnerKey(),
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
        val catalog: EntityLearningManager.HaAreaCatalog,
    )

    @Volatile private var haAreaCatalogCache: HaAreaCatalogCacheEntry? = null

    private fun haAreaCatalogKey(snapshot: HaAreaSnapshot): String =
        "${snapshot.ownerKey}|${snapshot.deviceUid}|${snapshot.panelId}"

    private fun cacheHaAreaCatalog(
        snapshot: HaAreaSnapshot,
        catalog: EntityLearningManager.HaAreaCatalog,
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
    ): EntityLearningManager.HaAreaCatalog {
        val key = haAreaCatalogKey(snapshot)
        val now = System.nanoTime() / 1_000_000L
        if (!fresh) {
            haAreaCatalogCache?.takeIf { entry ->
                haAreaCacheEntryUsable(entry.key, key, entry.cachedAtMs, now, HA_AREA_CATALOG_TTL_MS)
            }?.catalog?.let { return it }
        }
        val catalog = entityLearning.haAreaCatalog(snapshot.deviceUid, snapshot.panelId)
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
        snapshot.ownerKey == entityLearning.haAreaOwnerKey() &&
            snapshot.deviceUid == config.deviceUid && snapshot.panelId == config.panelId &&
            snapshot.localArea == config.haArea && snapshot.userOverride == config.haAreaUserOverride

    private suspend fun applyHaAreaPrecedence(
        snapshot: HaAreaSnapshot,
        catalog: EntityLearningManager.HaAreaCatalog,
        allowWriteBack: Boolean = true,
    ): EntityLearningManager.HaAreaCatalog {
        if (!catalog.queried || !catalog.device.found || catalog.ownerKey != snapshot.ownerKey ||
            !ownsHaAreaSnapshot(snapshot)
        ) return catalog
        if (allowWriteBack && catalog.admin &&
            HaAreaProtocol.hasLiteralNullAssignment(catalog.device, catalog.areas) &&
            ownsHaAreaSnapshot(snapshot)
        ) {
            val cleared = entityLearning.applyRequestedArea(
                snapshot.deviceUid, snapshot.panelId, "", snapshot.ownerKey,
            )
            if (cleared && ownsHaAreaSnapshot(snapshot)) {
                invalidateHaAreaCatalogCache()
                val after = entityLearning.haAreaCatalog(snapshot.deviceUid, snapshot.panelId)
                cacheHaAreaCatalog(snapshot, after)
                return applyHaAreaPrecedence(snapshot, after, allowWriteBack = false)
            }
        }
        when (HaAreaProtocol.reconcile(snapshot.localArea, catalog.device.areaName, catalog.admin, snapshot.userOverride)) {
            HaAreaProtocol.ReconcileAction.ADOPT_HA -> withContext(Dispatchers.IO) {
                synchronized(directConfigMutationLock) {
                    if (!ownsHaAreaSnapshot(snapshot)) return@synchronized
                    config.synchronizedTransaction {
                        if (!ownsHaAreaSnapshot(snapshot)) return@synchronizedTransaction false
                        Log.i(TAG, "ha-area: adopting Home Assistant's area for this device")
                        // Adoption is only reachable for a non-override value, or for an override that
                        // matches HA in a different casing — either way nothing local-only remains.
                        config.commitHaArea(catalog.device.areaName, userOverride = false)
                    }
                }
            }
            HaAreaProtocol.ReconcileAction.WRITE_BACK -> if (allowWriteBack && ownsHaAreaSnapshot(snapshot)) {
                val moved = entityLearning.applyRequestedArea(
                    snapshot.deviceUid,
                    snapshot.panelId,
                    snapshot.localArea,
                    snapshot.ownerKey,
                )
                if (moved && ownsHaAreaSnapshot(snapshot)) {
                    invalidateHaAreaCatalogCache()
                    val after = entityLearning.haAreaCatalog(snapshot.deviceUid, snapshot.panelId)
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
     * devices sat in real areas, so every surface honestly reported "No area" and discovery published no
     * `suggested_area`. One unprompted
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
                    val snapshot = captureHaAreaSnapshot()
                    runCatching {
                        applyHaAreaPrecedence(snapshot, haAreaCatalogFor(snapshot, fresh = true))
                    }
                        .onFailure { Log.w(TAG, "ha-area: unprompted convergence failed", it) }
                }
                delay(HA_AREA_REPEAT_MS)
            }
        }
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
        // Late enough that the first pass does not compete with boot (renderer, MQTT, profile activation),
        // early enough that a panel is correct long before anybody opens a settings page.
        // Long enough that a round of page reloads costs one Home Assistant read, short enough that an
        // admin who moves the device in HA sees it here without waiting for the six-hourly pass.
        private const val HA_AREA_CATALOG_TTL_MS = 10 * 60 * 1000L
        private const val HA_AREA_FIRST_PASS_MS = 45_000L
        private const val HA_AREA_REPEAT_MS = 6 * 60 * 60 * 1000L
    }
}
