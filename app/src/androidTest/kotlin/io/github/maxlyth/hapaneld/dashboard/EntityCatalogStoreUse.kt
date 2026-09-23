package io.github.maxlyth.hapaneld.dashboard

import java.io.Closeable

/** API 27-safe resource handling for the catalog store in instrumentation fixtures. */
internal inline fun <T> EntityCatalogStore.use(block: (EntityCatalogStore) -> T): T =
    Closeable { close() }.use { block(this) }
