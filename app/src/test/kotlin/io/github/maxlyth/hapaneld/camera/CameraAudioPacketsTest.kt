package io.github.maxlyth.hapaneld.camera

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAudioPacketsTest {
    @Test fun fragmentedAacCarriesItsFullAuSizeOneTimestampAndTheFinalMarkerWithSequenceWrap() {
        val unit = ByteArray(40) { it.toByte() }
        val packets = RtpAacPacketizer(123, 65535, maxPayload = 20).packetize(unit, 16_000L)
        assertEquals(3, packets.size)
        assertEquals(listOf(65535, 0, 1), packets.map { ByteBuffer.wrap(it).getShort(2).toInt() and 65535 })
        packets.forEach {
            val wire = ByteBuffer.wrap(it)
            assertEquals(16000, wire.getInt(4))
            assertEquals(123, wire.getInt(8))
            assertEquals(16, wire.getShort(12).toInt())
            assertEquals(40 shl 3, wire.getShort(14).toInt())
        }
        assertFalse(packets.first()[1].toInt() and 128 != 0)
        assertTrue(packets.last()[1].toInt() and 128 != 0)
        assertEquals(unit.toList(), packets.flatMap { it.drop(16) })
        assertTrue(RtpAacPacketizer(1, 0).packetize(ByteArray(8192), 0).isEmpty())
    }

    @Test fun senderReportsMapBothRtpClocksToOneCaptureTimeAndBoundReportFrequency() {
        val video = RtcpSender(1)
        val audio = RtcpSender(2)
        video.sent(ByteArray(112))
        audio.sent(ByteArray(16))
        val v = ByteBuffer.wrap(requireNotNull(video.report(90_000L, 1_000_000L, 3_000_000L, 10_000L)))
        val a = ByteBuffer.wrap(requireNotNull(audio.report(16_000L, 1_000_000L, 3_000_000L, 10_000L)))
        assertEquals(200, a.get(1).toInt() and 255)
        assertEquals(2, a.getInt(4))
        assertEquals(2_208_988_808L, a.getInt(8).toLong() and 0xffff_ffffL)
        assertEquals(v.getLong(8), a.getLong(8))
        assertEquals(16000, a.getInt(16))
        assertEquals(90000, v.getInt(16))
        assertEquals(1, a.getInt(20))
        assertEquals(4, a.getInt(24))
        assertEquals(100, v.getInt(24))
        assertEquals(202, a.get(29).toInt() and 255)
        assertEquals("ha-paneld", String(a.array().copyOfRange(38, 47), Charsets.US_ASCII))
        assertNull(audio.report(17_600, 1_100_000, 3_100_000, 10_100))
        assertTrue(audio.report(32_000, 2_000_000, 4_000_000, 11_000) != null)
    }
}
