package io.panelassistant.android.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 6184 on the wire, byte by byte, because a receiver that disagrees shows a grey card and nothing else. */
class RtpH264PacketizerTest {

    private fun seq(p: ByteArray) = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
    private fun marker(p: ByteArray) = p[1].toInt() and 0x80 != 0
    private fun payloadType(p: ByteArray) = p[1].toInt() and 0x7F
    private fun timestamp(p: ByteArray) =
        ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
    private fun ssrc(p: ByteArray) =
        ((p[8].toInt() and 0xFF) shl 24) or ((p[9].toInt() and 0xFF) shl 16) or ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
    private fun payload(p: ByteArray) = p.copyOfRange(12, p.size)

    @Test fun aSmallNalUnitTravelsInOneSingleNalPacketWithTheMarkerSet() {
        val packetizer = RtpH264Packetizer(ssrc = 0x11223344, firstSequence = 65534)
        val nal = byteArrayOf(0x65, 1, 2, 3)
        val packets = packetizer.packetize(listOf(nal), rtpTimestamp = 0x8000_0001L)
        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x80, p[0].toInt() and 0xFF)
        assertEquals(96, payloadType(p))
        assertTrue("last packet of the access unit", marker(p))
        assertEquals(65534, seq(p))
        assertEquals(0x8000_0001L, timestamp(p))
        assertEquals(0x11223344, ssrc(p))
        assertArrayEquals(nal, payload(p))
        assertEquals("sequence advanced for the next packet", 65535, packetizer.nextSequence)
    }

    @Test fun theMarkerBitBelongsToTheLastPacketOfTheAccessUnitOnly() {
        val packetizer = RtpH264Packetizer(ssrc = 1, firstSequence = 10)
        val sps = byteArrayOf(0x67, 0x42)
        val pps = byteArrayOf(0x68, 0xCE.toByte())
        val idr = byteArrayOf(0x65, 9, 9)
        val packets = packetizer.packetize(listOf(sps, pps, idr), rtpTimestamp = 90_000L)
        assertEquals(3, packets.size)
        assertFalse(marker(packets[0]))
        assertFalse(marker(packets[1]))
        assertTrue(marker(packets[2]))
        assertEquals(listOf(10, 11, 12), packets.map(::seq))
        assertTrue("one timestamp for the whole access unit", packets.all { timestamp(it) == 90_000L })
    }

    @Test fun aLargeNalUnitIsFragmentedAsFuAWithStartAndEndBitsAndTheTypePreserved() {
        val packetizer = RtpH264Packetizer(ssrc = 1, firstSequence = 0, maxPayload = 10)
        // NRI=3 (0x60), type 5 (IDR): header byte 0x65, then 20 bytes of slice data.
        val nal = byteArrayOf(0x65) + ByteArray(20) { (it + 1).toByte() }
        val packets = packetizer.packetize(listOf(nal), rtpTimestamp = 0L)
        // 20 bytes at 8 per fragment (10 minus the two FU bytes) is three fragments.
        assertEquals(3, packets.size)
        val first = payload(packets[0])
        assertEquals("FU indicator keeps the NRI and says FU-A", 0x7C, first[0].toInt() and 0xFF)
        assertEquals("start bit plus the original type", 0x85, first[1].toInt() and 0xFF)
        assertArrayEquals(nal.copyOfRange(1, 9), first.copyOfRange(2, first.size))
        val middle = payload(packets[1])
        assertEquals("neither start nor end", 0x05, middle[1].toInt() and 0xFF)
        val last = payload(packets[2])
        assertEquals("end bit plus type", 0x45, last[1].toInt() and 0xFF)
        assertArrayEquals(nal.copyOfRange(17, 21), last.copyOfRange(2, last.size))
        assertFalse(marker(packets[0]))
        assertFalse(marker(packets[1]))
        assertTrue("the last fragment ends the access unit", marker(packets[2]))
        assertEquals(listOf(0, 1, 2), packets.map(::seq))
    }

    @Test fun aNalUnitExactlyAtTheLimitIsNotFragmented() {
        val packetizer = RtpH264Packetizer(ssrc = 1, firstSequence = 0, maxPayload = 10)
        val nal = ByteArray(10) { 0x41 }
        val packets = packetizer.packetize(listOf(nal), rtpTimestamp = 0L)
        assertEquals(1, packets.size)
        assertArrayEquals(nal, payload(packets[0]))
    }

    @Test fun sequenceNumbersWrapAtSixteenBitsAndEmptyInputSendsNothing() {
        val packetizer = RtpH264Packetizer(ssrc = 1, firstSequence = 65535)
        val a = packetizer.packetize(listOf(byteArrayOf(0x41, 1)), rtpTimestamp = 1L)
        val b = packetizer.packetize(listOf(byteArrayOf(0x41, 2)), rtpTimestamp = 2L)
        assertEquals(65535, seq(a[0]))
        assertEquals(0, seq(b[0]))
        assertTrue(packetizer.packetize(emptyList(), rtpTimestamp = 3L).isEmpty())
        assertTrue(packetizer.packetize(listOf(ByteArray(0)), rtpTimestamp = 3L).isEmpty())
        assertEquals("nothing was consumed", 1, packetizer.nextSequence)
    }

    @Test fun theRtpClockIsNinetyKilohertzModuloThirtyTwoBits() {
        assertEquals(90_000L, RtpH264Packetizer.rtpTimestamp(1_000_000L))
        assertEquals(45L, RtpH264Packetizer.rtpTimestamp(500L))
        assertEquals(90_045L, RtpH264Packetizer.rtpTimestamp(1_000_500L))
        // 100,000 seconds is 9,000,000,000 ticks, which exceeds the 32-bit field and must wrap.
        assertEquals("wraps rather than overflowing the field", 9_000_000_000L and 0xFFFF_FFFFL, RtpH264Packetizer.rtpTimestamp(100_000_000_000L))
        // Six years of uptime in microseconds: the naive product with 90,000 overflows a Long.
        val years = 200_000_000L * 1_000_000L
        assertEquals((200_000_000L * 90_000L) and 0xFFFF_FFFFL, RtpH264Packetizer.rtpTimestamp(years))
        assertTrue(RtpH264Packetizer.rtpTimestamp(years) in 0L..0xFFFF_FFFFL)
    }

    @Test fun anInterleavedFrameCarriesTheChannelAndABigEndianLength() {
        val packet = ByteArray(300) { it.toByte() }
        val frame = RtspInterleaved.frame(2, packet)
        assertEquals(304, frame.size)
        assertEquals('$'.code, frame[0].toInt())
        assertEquals(2, frame[1].toInt())
        assertEquals(1, frame[2].toInt())
        assertEquals(44, frame[3].toInt())
        assertArrayEquals(packet, frame.copyOfRange(4, frame.size))
    }
}
