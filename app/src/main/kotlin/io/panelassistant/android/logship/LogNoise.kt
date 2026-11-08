package io.panelassistant.android.logship

/** Bounded aggregation shared by every normal capture consumer. Raw viewers bypass it. */
internal class LogNoise(private val render: (LogCaptureRecord) -> String) {
    private data class Count(val record: LogCaptureRecord, val reason: String, var records: Long = 0, var bytes: Long = 0)
    private val counts = LinkedHashMap<String, Count>()

    fun accept(line: String, record: LogCaptureRecord?, rules: Set<String>): List<String> {
        if (record == null) return listOf(line)
        val known = if ("selinux-permissive" in rules && isPermissiveAudit(record)) "selinux-permissive" else null
        val key = known ?: if (record.priority >= 5) "${record.priority}\u0000${record.tag}\u0000${record.message}" else return listOf(line)
        val output = mutableListOf<String>()
        var count = counts[key]
        if (count == null) {
            if (counts.size >= MAX_KEYS) {
                val oldest = counts.entries.first()
                summary(oldest.value)?.let(output::add)
                counts.remove(oldest.key)
            }
            count = Count(record, known ?: "repeated warning from ${record.tag}")
            counts[key] = count
            if (known == null) {
                output += line // First warning is always immediate, with its original metadata.
                return output
            }
        }
        count.records++
        count.bytes += line.toByteArray(Charsets.UTF_8).size
        return output
    }

    fun flush(): List<String> = counts.values.mapNotNull(::summary).also { counts.clear() }

    private fun summary(count: Count): String? = if (count.records == 0L) null else render(count.record.copy(
        tag = "ha-paneld/log-noise",
        message = "${count.reason}: collapsed ${count.records} records (${count.bytes} UTF-8 bytes); raw logs available on demand",
    ))

    companion object {
        private const val MAX_KEYS = 64
        private val PERMISSIVE = Regex("(?:^|\\s)permissive=1(?:\\s|$)")
        private val AVC_DENIED = Regex("\\):\\s+avc:\\s+denied(?:\\s|$)")

        private fun isPermissiveAudit(record: LogCaptureRecord): Boolean =
            record.priority < 6 && record.message.startsWith("type=1400 audit(") &&
                AVC_DENIED.containsMatchIn(record.message) && PERMISSIVE.containsMatchIn(record.message)
    }
}
