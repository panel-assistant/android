package io.panelassistant.android.control

/** Bounded acknowledgement journal used to distinguish our Settings writes from another app's. */
internal class BrightnessWriteAttribution(
    private val windowMs: Long = 3_000L,
    private val maximumEntries: Int = 16,
) {
    private data class Entry(val level: Int, val writtenAtMs: Long)
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(level: Int, nowMs: Long) {
        prune(nowMs)
        entries.addLast(Entry(level.coerceIn(0, 255), nowMs))
        while (entries.size > maximumEntries) entries.removeFirst()
    }

    @Synchronized
    fun consume(level: Int, nowMs: Long): Boolean {
        prune(nowMs)
        val index = entries.indexOfLast { it.level == level }
        if (index < 0) return false
        entries.removeAt(index)
        return true
    }

    @Synchronized internal fun sizeForTest(): Int = entries.size

    private fun prune(nowMs: Long) {
        while (entries.firstOrNull()?.let { nowMs < it.writtenAtMs || nowMs - it.writtenAtMs > windowMs } == true) {
            entries.removeFirst()
        }
    }
}
