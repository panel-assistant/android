package io.panelassistant.android.config

/**
 * Pure ring-buffer math for the on-panel config revision history. The store keeps the last [MAX]
 * snapshots; this decides which existing revision ids to evict when a new one is added. Kept separate
 * from the Android-backed RevisionStore (filesystem) so the policy is unit-testable.
 */
object RevisionRing {

    const val MAX = 20

    /**
     * Given the [existing] revision ids and a [max] cap, return the ids to delete so that — once a new
     * revision is added — no more than [max] remain. Oldest (lowest id) are evicted first.
     */
    fun toEvict(existing: List<Long>, max: Int = MAX): List<Long> {
        // After adding one, total = existing.size + 1; keep the newest `max`.
        val overflow = existing.size + 1 - max
        if (overflow <= 0) return emptyList()
        return existing.sorted().take(overflow)
    }
}
