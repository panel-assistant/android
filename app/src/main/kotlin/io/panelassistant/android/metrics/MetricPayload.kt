package io.panelassistant.android.metrics

/**
 * A compact encoding of "which measurements this bucket holds", so that adding one is a data change.
 *
 * Fixed metric columns make the metric list part of the schema: each new probe costs an `ALTER TABLE`,
 * a schema-version bump, a migration and a fleet release — and every bump creates a future downgrade
 * that can reset a panel's configuration. In practice that means the instrumentation never gets added
 * at the moment it is actually needed, which is the opposite of what a diagnostic table is for.
 *
 * Measured against a real panel's `dashboard_performance` (4,445 buckets, 22 metrics, 450,560 bytes):
 *
 * - fixed columns as today: 450,560 bytes
 * - one fact row per measurement: 1,384,448 bytes (3.07x) — rejected; the key is repeated per value and
 *   the data is 64% dense, so sparse writes do not rescue it
 * - this encoding: 299,008 bytes (**0.66x**)
 *
 * It is smaller than the fixed columns it replaces because a bucket stores only the measurements it
 * actually has, and small integers cost one or two bytes. Pairs are `(id, value)`, both zigzag varints:
 * zigzag so a negative value costs the same as a small positive one rather than ten bytes.
 *
 * Ids are stable and allocated once per metric; an unrecognised id decodes to a value a reader can
 * simply ignore, which is what lets an older build read a newer panel's history without a schema change.
 */
object MetricPayload {
    /** Encodes non-zero measurements only; a bucket where nothing happened costs an empty payload. */
    fun encode(values: Map<Int, Long>): ByteArray {
        val out = ArrayList<Byte>(values.size * 3)
        values.entries.sortedBy { it.key }.forEach { (id, value) ->
            if (value == 0L) return@forEach
            writeVarint(out, id.toLong())
            writeVarint(out, value)
        }
        return out.toByteArray()
    }

    /**
     * Decodes to id/value pairs. Returns null for a truncated or malformed payload rather than a partial
     * reading: a corrupt diagnostic row should be skipped, never silently reported as real measurements.
     */
    fun decode(payload: ByteArray?): Map<Int, Long>? {
        if (payload == null) return null
        if (payload.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, Long>()
        var index = 0
        while (index < payload.size) {
            val id = readVarint(payload, index) ?: return null
            val value = readVarint(payload, id.next) ?: return null
            if (id.value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
            out[id.value.toInt()] = value.value
            index = value.next
        }
        return out
    }

    private class Read(val value: Long, val next: Int)

    private fun writeVarint(out: MutableList<Byte>, raw: Long) {
        var encoded = (raw shl 1) xor (raw shr 63) // zigzag
        while (true) {
            val part = (encoded and 0x7F).toInt()
            encoded = encoded ushr 7
            if (encoded == 0L) {
                out.add(part.toByte()); return
            }
            out.add((part or 0x80).toByte())
        }
    }

    private fun readVarint(payload: ByteArray, start: Int): Read? {
        var shift = 0
        var accumulated = 0L
        var index = start
        while (index < payload.size) {
            val byte = payload[index].toInt()
            if (shift > 63) return null // more continuation bytes than a 64-bit value can hold
            accumulated = accumulated or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) {
                val zigzag = (accumulated ushr 1) xor -(accumulated and 1) // undo zigzag
                return Read(zigzag, index)
            }
            shift += 7
        }
        return null // ran off the end mid-value
    }
}
