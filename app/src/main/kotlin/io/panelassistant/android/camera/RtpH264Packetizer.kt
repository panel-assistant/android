package io.panelassistant.android.camera

/**
 * RFC 6184 packetisation of one H.264 access unit into RTP packets, for one client: sequence numbers
 * and the SSRC are per client, so each connection gets its own instance. Single-NAL-unit packets carry
 * a NAL unit that fits; anything larger is fragmented as FU-A. The marker bit is set on the last packet
 * of the access unit, which is how a receiver knows the frame is complete. No STAP-A: the parameter
 * sets go out as ordinary single-NAL packets ahead of the IDR, which every decoder understands.
 */
class RtpH264Packetizer(
    val ssrc: Int,
    firstSequence: Int,
    private val maxPayload: Int = DEFAULT_MAX_PAYLOAD,
    private val payloadType: Int = PAYLOAD_TYPE,
) {
    init {
        require(maxPayload > 2) { "maxPayload must leave room for an FU-A header" }
    }

    /** The sequence number the next packet will carry, for `RTP-Info` on PLAY. */
    var nextSequence: Int = firstSequence and 0xFFFF
        private set

    /** Every RTP packet for [nals] at [rtpTimestamp], in order; empty when there is nothing to send. */
    fun packetize(nals: List<ByteArray>, rtpTimestamp: Long): List<ByteArray> {
        val units = nals.filter { it.isNotEmpty() }
        if (units.isEmpty()) return emptyList()
        val packets = ArrayList<ByteArray>()
        units.forEachIndexed { index, nal ->
            val lastUnit = index == units.lastIndex
            if (nal.size <= maxPayload) {
                packets += packet(nal, 0, nal.size, rtpTimestamp, marker = lastUnit, prefix = null)
            } else {
                val indicator = ((nal[0].toInt() and 0xE0) or FU_A).toByte()
                val type = (nal[0].toInt() and 0x1F).toByte()
                val chunk = maxPayload - 2
                var offset = 1
                while (offset < nal.size) {
                    val length = minOf(chunk, nal.size - offset)
                    val start = offset == 1
                    val end = offset + length == nal.size
                    val header = (type.toInt() or (if (start) 0x80 else 0) or (if (end) 0x40 else 0)).toByte()
                    packets += packet(nal, offset, length, rtpTimestamp, marker = lastUnit && end, prefix = byteArrayOf(indicator, header))
                    offset += length
                }
            }
        }
        return packets
    }

    private fun packet(source: ByteArray, offset: Int, length: Int, timestamp: Long, marker: Boolean, prefix: ByteArray?): ByteArray {
        val prefixSize = prefix?.size ?: 0
        val out = ByteArray(HEADER_SIZE + prefixSize + length)
        out[0] = 0x80.toByte()
        out[1] = ((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        out[2] = (nextSequence ushr 8).toByte()
        out[3] = nextSequence.toByte()
        val ts = timestamp and 0xFFFF_FFFFL
        out[4] = (ts ushr 24).toByte()
        out[5] = (ts ushr 16).toByte()
        out[6] = (ts ushr 8).toByte()
        out[7] = ts.toByte()
        out[8] = (ssrc ushr 24).toByte()
        out[9] = (ssrc ushr 16).toByte()
        out[10] = (ssrc ushr 8).toByte()
        out[11] = ssrc.toByte()
        if (prefix != null) System.arraycopy(prefix, 0, out, HEADER_SIZE, prefixSize)
        System.arraycopy(source, offset, out, HEADER_SIZE + prefixSize, length)
        nextSequence = (nextSequence + 1) and 0xFFFF
        return out
    }

    companion object {
        const val HEADER_SIZE = 12
        const val PAYLOAD_TYPE = 96
        const val FU_A = 28
        /** Fits a 1500-byte MTU with IP/UDP/RTP headers to spare, so the same packets would carry over UDP. */
        const val DEFAULT_MAX_PAYLOAD = 1400
        const val CLOCK_RATE = 90_000L

        /** Microsecond presentation time to the 90 kHz RTP clock, modulo 2^32; split so the product cannot overflow. */
        fun rtpTimestamp(ptsUs: Long): Long {
            val seconds = ptsUs / 1_000_000L
            val remainderUs = ptsUs % 1_000_000L
            return (seconds * CLOCK_RATE + remainderUs * CLOCK_RATE / 1_000_000L) and 0xFFFF_FFFFL
        }
    }
}

/** RTP over the RTSP control connection (RFC 2326 §10.12): `$`, channel, 16-bit length, packet. */
object RtspInterleaved {
    const val MAGIC: Byte = 0x24

    fun frame(channel: Int, packet: ByteArray): ByteArray {
        require(packet.size <= 0xFFFF) { "an interleaved frame carries at most 65535 bytes" }
        val out = ByteArray(4 + packet.size)
        out[0] = MAGIC
        out[1] = channel.toByte()
        out[2] = (packet.size ushr 8).toByte()
        out[3] = packet.size.toByte()
        System.arraycopy(packet, 0, out, 4, packet.size)
        return out
    }
}
