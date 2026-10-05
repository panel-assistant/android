package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The ICMP wire format, held to account without a device.
 *
 * Every rule here is one that fails SILENTLY when it is wrong — the probe simply never matches a
 * reply and the path reads as totally lost, which is the worst possible failure for a feature whose
 * entire job is to avoid false accusations. The syscalls themselves cannot be exercised on the JVM
 * (`android.system.Os` is not available in a unit test), so they are proved on hardware instead;
 * this covers everything else.
 */
class IcmpEchoPacketTest {
    private fun reply(v6: Boolean, seq: Int, token: Int, identifier: Int = 0xBEEF): ByteArray {
        val bytes = ByteArray(IcmpEchoPacket.HEADER_BYTES + IcmpEchoPacket.PAYLOAD_BYTES)
        bytes[0] = (if (v6) IcmpEchoPacket.ICMPV6_ECHO_REPLY else IcmpEchoPacket.ICMP_ECHO_REPLY).toByte()
        bytes[4] = ((identifier shr 8) and 0xFF).toByte()
        bytes[5] = (identifier and 0xFF).toByte()
        bytes[6] = ((seq shr 8) and 0xFF).toByte()
        bytes[7] = (seq and 0xFF).toByte()
        ByteBuffer.wrap(bytes, IcmpEchoPacket.HEADER_BYTES, 4).putInt(token)
        return bytes
    }

    @Test fun anEchoRequestCarriesTheTypeSequenceAndToken() {
        val packet = IcmpEchoPacket.request(v6 = false, seq = 0x0102, token = 0x11223344)
        assertEquals(IcmpEchoPacket.ICMP_ECHO_REQUEST, packet[0].toInt() and 0xFF)
        assertEquals(0, packet[1].toInt())
        assertEquals(0x01, packet[6].toInt() and 0xFF)
        assertEquals(0x02, packet[7].toInt() and 0xFF)
        assertEquals(0x11223344, ByteBuffer.wrap(packet, IcmpEchoPacket.HEADER_BYTES, 4).int)
        // The identifier is left zero on purpose: the kernel overwrites it on an unprivileged socket.
        assertEquals(0, packet[4].toInt())
        assertEquals(0, packet[5].toInt())
    }

    @Test fun theIpv4ChecksumIsPresentAndVerifiesToZeroOverTheWholePacket() {
        val packet = IcmpEchoPacket.request(v6 = false, seq = 7, token = 0x0BADF00D.toInt())
        assertTrue("a v4 request must carry a checksum", (packet[2].toInt() or packet[3].toInt()) != 0)
        // The defining property of the internet checksum: summing a packet that already carries a
        // correct one yields zero. This is what a kernel or a peer will actually check.
        assertEquals(0, IcmpEchoPacket.checksum(packet))
    }

    @Test fun theIpv6RequestLeavesTheChecksumToTheKernel() {
        val packet = IcmpEchoPacket.request(v6 = true, seq = 3, token = 42)
        assertEquals(IcmpEchoPacket.ICMPV6_ECHO_REQUEST, packet[0].toInt() and 0xFF)
        assertEquals("ICMPv6 checksums cover a pseudo-header only the kernel has", 0, packet[2].toInt())
        assertEquals(0, packet[3].toInt())
    }

    @Test fun aReplyIsMatchedOnSequenceAndTokenWhateverTheIdentifierSays() {
        // The whole point: the kernel rewrites the identifier on an unprivileged ping socket, so a
        // matcher that compared it would reject every genuine reply and report a dead path.
        val bytes = reply(v6 = false, seq = 9, token = 0x5EED, identifier = 0x1234)
        assertTrue(IcmpEchoPacket.matches(bytes, bytes.size, v6 = false, seq = 9, token = 0x5EED))
        val other = reply(v6 = false, seq = 9, token = 0x5EED, identifier = 0xFFFF)
        assertTrue("a different identifier must still match", IcmpEchoPacket.matches(other, other.size, false, 9, 0x5EED))
    }

    @Test fun aReplyToAnAbandonedEchoIsNotMatched() {
        // A late reply to an earlier sequence must never be credited to the one being waited on, or
        // the probe reports a round trip that never happened.
        val stale = reply(v6 = false, seq = 8, token = 0x5EED)
        assertFalse(IcmpEchoPacket.matches(stale, stale.size, v6 = false, seq = 9, token = 0x5EED))
    }

    @Test fun aReplyFromAnotherBurstIsNotMatched() {
        // Same sequence, different token: a burst that overlapped an earlier one would otherwise
        // count a stranger's reply as its own.
        val foreign = reply(v6 = false, seq = 9, token = 0x1111)
        assertFalse(IcmpEchoPacket.matches(foreign, foreign.size, v6 = false, seq = 9, token = 0x2222))
    }

    @Test fun theWrongTypeIsNotMatchedInEitherFamily() {
        val v4 = reply(v6 = false, seq = 1, token = 5)
        assertFalse("a v6 matcher must reject a v4 reply", IcmpEchoPacket.matches(v4, v4.size, v6 = true, 1, 5))
        val v6 = reply(v6 = true, seq = 1, token = 5)
        assertFalse("a v4 matcher must reject a v6 reply", IcmpEchoPacket.matches(v6, v6.size, v6 = false, 1, 5))
        assertTrue(IcmpEchoPacket.matches(v6, v6.size, v6 = true, 1, 5))
    }

    @Test fun aTruncatedDatagramIsRejectedRatherThanReadPastItsEnd() {
        val bytes = reply(v6 = false, seq = 1, token = 5)
        // Length is what the socket actually read, not the buffer's capacity.
        assertFalse(IcmpEchoPacket.matches(bytes, IcmpEchoPacket.HEADER_BYTES, v6 = false, seq = 1, token = 5))
        assertFalse(IcmpEchoPacket.matches(bytes, 0, v6 = false, seq = 1, token = 5))
    }

    @Test fun theSequenceWrapsWithSixteenBitsRatherThanMismatching() {
        // Sequence is a 16-bit field; a burst counter that ran past it must still match its replies.
        val bytes = reply(v6 = false, seq = 0x0001, token = 7)
        assertTrue(IcmpEchoPacket.matches(bytes, bytes.size, v6 = false, seq = 0x10001, token = 7))
    }
}
