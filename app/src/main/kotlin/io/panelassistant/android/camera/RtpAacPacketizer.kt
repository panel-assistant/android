package io.panelassistant.android.camera

/** RFC 3640 AAC-hbr, one raw AAC-LC access unit (no ADTS), bounded fragments. */
class RtpAacPacketizer(val ssrc: Int, firstSequence: Int, private val maxPayload: Int = 1400) {
    var nextSequence = firstSequence and 0xFFFF
        private set

    init { require(maxPayload > 4) }

    fun packetize(unit: ByteArray, timestamp: Long): List<ByteArray> {
        if (unit.isEmpty() || unit.size > 8191) return emptyList()
        val packets = ArrayList<ByteArray>()
        var offset = 0
        while (offset < unit.size) {
            val size = minOf(maxPayload - 4, unit.size - offset)
            val packet = java.nio.ByteBuffer.allocate(16 + size)
            packet.put(0x80.toByte()).put(((if (offset + size == unit.size) 0x80 else 0) or 97).toByte())
            packet.putShort(nextSequence.toShort()).putInt(timestamp.toInt()).putInt(ssrc)
            packet.putShort(16).putShort((unit.size shl 3).toShort())
            packet.put(unit, offset, size)
            packets += packet.array()
            nextSequence = (nextSequence + 1) and 0xFFFF
            offset += size
        }
        return packets
    }

    companion object {
        fun rtpTimestamp(ptsUs: Long): Long =
            ((ptsUs / 1_000_000L) * 16_000L + (ptsUs % 1_000_000L) * 16_000L / 1_000_000L) and 0xFFFF_FFFFL
    }
}
