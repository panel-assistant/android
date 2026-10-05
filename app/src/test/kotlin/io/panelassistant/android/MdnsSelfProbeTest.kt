package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsSelfProbeTest {
    @Test fun queryRequestsTheExactInstanceWithAnyRecordType() {
        val packet = mdnsQuery("panel-a._ha-paneld._tcp.local.", queryId = 0x1234)
        val label = byteArrayOf(7) + "panel-a".encodeToByteArray()

        assertEquals(0x12, packet[0].toInt() and 0xff)
        assertEquals(0x34, packet[1].toInt() and 0xff)
        assertEquals(1, packet[5].toInt())
        assertEquals(255, packet[packet.size - 3].toInt() and 0xff)
        assertEquals(0x80, packet[packet.size - 2].toInt() and 0xff)
        assertTrue(packet.toList().windowed(label.size).any { it == label.toList() })
    }

    @Test fun loopedBackQueryIsNotMistakenForAResponse() {
        val query = mdnsQuery("panel-a._ha-paneld._tcp.local.", queryId = 7)

        assertFalse(mdnsResponseAnswersQuery(query, query.size, 7, "panel-a._ha-paneld._tcp.local."))
    }

    @Test fun responseRequiresExactQuestionAndExactSrvOrTxtAnswer() {
        val response = responseFor("panel-a._ha-paneld._tcp.local.", queryId = 9)

        assertTrue(mdnsResponseAnswersQuery(response, response.size, 9, "panel-a._ha-paneld._tcp.local."))
        assertFalse(mdnsResponseAnswersQuery(response, response.size, 8, "panel-a._ha-paneld._tcp.local."))
        assertFalse(mdnsResponseAnswersQuery(response, response.size, 9, "panel-a._other._tcp.local."))
    }

    @Test fun responseFromThisAdvertisersAddressAndResponderPortIsAccepted() {
        val response = responseFor("panel-a._ha-paneld._tcp.local.", queryId = 9)

        assertTrue(mdnsResponseAnswersQuery(response, response.size, 9,
            "panel-a._ha-paneld._tcp.local.", "192.0.2.109", 5353, "192.0.2.109", "secret"))
    }

    @Test fun validPayloadFromPeerOrEphemeralSocketIsRejected() {
        val response = responseFor("panel-a._ha-paneld._tcp.local.", queryId = 9)

        assertFalse(mdnsResponseAnswersQuery(response, response.size, 9,
            "panel-a._ha-paneld._tcp.local.", "192.0.2.110", 5353, "192.0.2.109"))
        assertFalse(mdnsResponseAnswersQuery(response, response.size, 9,
            "panel-a._ha-paneld._tcp.local.", "192.0.2.109", 49152, "192.0.2.109"))
        assertFalse(mdnsResponseAnswersQuery(response, response.size, 9,
            "panel-a._ha-paneld._tcp.local.", "127.0.0.1", 5353, "192.0.2.109"))
        assertFalse(mdnsResponseAnswersQuery(response, response.size, 9,
            "panel-a._ha-paneld._tcp.local.", "192.0.2.109", 5353, "192.0.2.109", "wrong"))
    }

    @Test fun wrongInterfaceOrOldGenerationIsInconclusiveRatherThanAMiss() {
        val response = responseFor("panel-a._ha-paneld._tcp.local.", queryId = 9)

        assertEquals(MdnsProbeResult.INCONCLUSIVE, classifyMdnsProbeResponse(
            response, response.size, 9, "panel-a._ha-paneld._tcp.local.",
            "192.0.2.110", 5353, "192.0.2.109", "secret",
        ))
        assertEquals(MdnsProbeResult.INCONCLUSIVE, classifyMdnsProbeResponse(
            response, response.size, 9, "panel-a._ha-paneld._tcp.local.",
            "192.0.2.109", 5353, "192.0.2.109", "new-generation-token",
        ))
        assertEquals(MdnsProbeResult.VISIBLE, classifyMdnsProbeResponse(
            response, response.size, 9, "panel-a._ha-paneld._tcp.local.",
            "192.0.2.109", 5353, "192.0.2.109", "secret",
        ))
    }

    @Test fun qrPacketWithoutAnAnswerAndMalformedCompressionAreRejected() {
        val noAnswer = mdnsQuery("panel-a._ha-paneld._tcp.local.", queryId = 11).apply {
            this[2] = 0x84.toByte()
        }
        assertFalse(mdnsResponseAnswersQuery(noAnswer, noAnswer.size, 11, "panel-a._ha-paneld._tcp.local."))

        val malformed = responseFor("panel-a._ha-paneld._tcp.local.", queryId = 12).apply {
            val answerOffset = mdnsQuery("panel-a._ha-paneld._tcp.local.", 12).size
            this[answerOffset] = 0xc0.toByte()
            this[answerOffset + 1] = 0xff.toByte()
        }
        assertFalse(mdnsResponseAnswersQuery(malformed, malformed.size, 12, "panel-a._ha-paneld._tcp.local."))
    }

    private fun responseFor(name: String, queryId: Int): ByteArray {
        val query = mdnsQuery(name, queryId)
        val answer = byteArrayOf(
            0xc0.toByte(), 0x0c, // owner name compressed to the question
            0x00, 0x10, // TXT
            0x00, 0x01, // IN
            0x00, 0x00, 0x00, 0x78, // TTL
            0x00, 0x0d, // RDLENGTH
            0x0c, // TXT string length
            'p'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), 'e'.code.toByte(),
            '='.code.toByte(), 's'.code.toByte(), 'e'.code.toByte(), 'c'.code.toByte(), 'r'.code.toByte(),
            'e'.code.toByte(), 't'.code.toByte(),
        )
        return (query + answer).apply {
            this[2] = 0x84.toByte()
            this[7] = 1 // ANCOUNT
        }
    }
}
