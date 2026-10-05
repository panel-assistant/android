package io.panelassistant.android.camera

/** Media-driven compound sender report + common CNAME maps both RTP clocks to wall time. */
internal class RtcpSender(private val ssrc: Int) {
    private var packets = 0
    private var octets = 0
    private var reportedAt: Long? = null

    fun sent(packet: ByteArray) { packets++; octets += packet.size - 12 }

    fun report(timestamp: Long, ptsUs: Long, clockUs: Long, wallMs: Long): ByteArray? {
        if (reportedAt?.let { ptsUs - it < 1_000_000L } == true) return null
        reportedAt = ptsUs
        val mediaWallMs = wallMs - (clockUs - ptsUs) / 1_000L
        val seconds = Math.floorDiv(mediaWallMs, 1000L) + 2_208_988_800L
        val fraction = Math.floorMod(mediaWallMs, 1000L) * 0x1_0000_0000L / 1000L
        return java.nio.ByteBuffer.allocate(48).apply {
            put(0x80.toByte()).put(200.toByte()).putShort(6)
            putInt(ssrc).putInt(seconds.toInt()).putInt(fraction.toInt())
            putInt(timestamp.toInt()).putInt(packets).putInt(octets)
            put(0x81.toByte()).put(202.toByte()).putShort(4).putInt(ssrc)
            put(1).put(9).put("ha-paneld".toByteArray(Charsets.US_ASCII)).put(0)
            while (position() < capacity()) put(0)
        }.array()
    }
}
