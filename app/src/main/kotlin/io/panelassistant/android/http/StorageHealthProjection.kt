package io.panelassistant.android.http

import io.panelassistant.android.storage.StorageHealthSnapshot

/** One request-scoped status refresh. A failed/incomplete fresh database observation must not fall back
 * to a previously healthy snapshot, because provisioning treats schema + quick_check as admission proof. */
internal data class RefreshedStatusStorage(
    val snapshot: StorageHealthSnapshot,
    val fresh: Boolean,
)

internal suspend fun refreshedStatusStorage(
    refreshRequested: Boolean,
    refreshUpdates: suspend () -> Unit,
    refreshStorage: suspend () -> StorageHealthSnapshot?,
    cachedStorage: () -> StorageHealthSnapshot,
): RefreshedStatusStorage {
    if (!refreshRequested) return RefreshedStatusStorage(cachedStorage(), fresh = false)
    refreshUpdates()
    val fresh = refreshStorage()
    return RefreshedStatusStorage(fresh ?: StorageHealthSnapshot.UNCHECKED, fresh = fresh != null)
}

internal fun validDatabaseObservationNonce(raw: String?): String? = raw?.takeIf {
    it.length == 32 && it.all { char -> char in '0'..'9' || char in 'a'..'f' }
}

internal fun databaseObservationProof(
    refreshRequested: Boolean,
    rawNonce: String?,
    observation: RefreshedStatusStorage,
): String? = validDatabaseObservationNonce(rawNonce).takeIf { refreshRequested && observation.fresh }
