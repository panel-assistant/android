package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore

internal interface ProximityModelStore : AutoCloseable {
    fun readProximityModel(fingerprint: String): EntityCatalogStore.ProximityModelRow?
    fun writeProximityBatch(
        model: EntityCatalogStore.ProximityModelRow,
        rollups: List<EntityCatalogStore.ProximityRollupRow>,
        episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
        now: Long,
    )
    fun clearProximityLearning(fingerprint: String)
}

internal class SqliteProximityModelStore(
    private val store: ProximityModelStore,
) : ProximityModelStore {
    constructor(store: EntityCatalogStore) : this(EntityCatalogProximityModelStore(store))

    override fun readProximityModel(fingerprint: String) = store.readProximityModel(fingerprint)

    override fun writeProximityBatch(
        model: EntityCatalogStore.ProximityModelRow,
        rollups: List<EntityCatalogStore.ProximityRollupRow>,
        episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
        now: Long,
    ) = store.writeProximityBatch(model, rollups, episodes, now)

    override fun clearProximityLearning(fingerprint: String) = store.clearProximityLearning(fingerprint)

    override fun close() = store.close()
}

private class EntityCatalogProximityModelStore(
    private val store: EntityCatalogStore,
) : ProximityModelStore {
    override fun readProximityModel(fingerprint: String) = store.readProximityModel(fingerprint)

    override fun writeProximityBatch(
        model: EntityCatalogStore.ProximityModelRow,
        rollups: List<EntityCatalogStore.ProximityRollupRow>,
        episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
        now: Long,
    ) = store.writeProximityBatch(model, rollups, episodes, now)

    override fun clearProximityLearning(fingerprint: String) = store.clearProximityLearning(fingerprint)

    override fun close() = store.close()
}
