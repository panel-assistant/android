package io.github.maxlyth.hapaneld

import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

internal enum class MdnsProbeResult { VISIBLE, MISSING, INCONCLUSIVE, UNAVAILABLE }

/**
 * Independent on-wire check of the responder: ask multicast DNS for this exact service instance and
 * require a response packet that names it. Unlike JmDNS.list(), this cannot be satisfied from JmDNS's
 * persistent ServiceCollector cache. An unavailable probe is never evidence that the responder died.
 */
internal fun probeMdnsService(
    lanIp: String,
    instanceName: String,
    serviceType: String,
    probeToken: String,
    timeoutMs: Int = 1_500,
): MdnsProbeResult {
    val local = runCatching { InetAddress.getByName(lanIp) }.getOrNull() ?: return MdnsProbeResult.UNAVAILABLE
    val localIp = local.hostAddress ?: return MdnsProbeResult.UNAVAILABLE
    val network = runCatching { NetworkInterface.getByInetAddress(local) }.getOrNull()
        ?: return MdnsProbeResult.UNAVAILABLE
    val group = InetAddress.getByName(mdnsGroupFor(local))
    // An IPv6 responder's unicast reply leaves from whichever of the interface's addresses the kernel
    // selects, often a temporary one, never necessarily the one JmDNS is bound to. Any address of the
    // bound interface is this panel; IPv4 keeps its exact-source rule.
    val ownSources = if (local is Inet6Address) {
        network.inetAddresses.toList().mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    } else emptySet()
    val queryName = "$instanceName.${serviceType.trimEnd('.')}."
    val queryId = ThreadLocalRandom.current().nextInt(1, 65_536)
    val query = runCatching { mdnsQuery(queryName, queryId) }.getOrNull()
        ?: return MdnsProbeResult.UNAVAILABLE
    return try {
        MulticastSocket(null).use { socket ->
            // A non-5353 source port asks RFC 6762 responders (including JmDNS) for a unicast reply.
            // This avoids sharing JmDNS's listener socket while still proving multicast query handling.
            socket.bind(InetSocketAddress(local, 0))
            socket.networkInterface = network
            socket.send(DatagramPacket(query, query.size, group, MDNS_PORT))
            val response = ByteArray(MAX_PACKET)
            var inconclusiveResponseSeen = false
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1).toLong())
            while (true) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0L) return@use if (inconclusiveResponseSeen) {
                    MdnsProbeResult.INCONCLUSIVE
                } else MdnsProbeResult.MISSING
                socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(response, response.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    return@use if (inconclusiveResponseSeen) {
                        MdnsProbeResult.INCONCLUSIVE
                    } else MdnsProbeResult.MISSING
                }
                when (classifyMdnsProbeResponse(
                    packet.data, packet.length, queryId, queryName,
                    packet.address?.hostAddress, packet.port,
                    packet.address?.hostAddress?.takeIf { it.substringBefore('%') in ownSources } ?: localIp,
                    probeToken,
                )) {
                    MdnsProbeResult.VISIBLE -> return@use MdnsProbeResult.VISIBLE
                    MdnsProbeResult.INCONCLUSIVE -> inconclusiveResponseSeen = true
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE")
            MdnsProbeResult.MISSING
        }
    } catch (_: Exception) {
        MdnsProbeResult.UNAVAILABLE
    }
}

internal fun classifyMdnsProbeResponse(
    packet: ByteArray,
    length: Int,
    queryId: Int,
    qualifiedName: String,
    sourceIp: String?,
    sourcePort: Int?,
    expectedSourceIp: String,
    expectedProbeToken: String,
): MdnsProbeResult {
    if (!mdnsResponseAnswersQuery(packet, length, queryId, qualifiedName)) return MdnsProbeResult.MISSING
    return if (mdnsResponseAnswersQuery(
            packet, length, queryId, qualifiedName,
            sourceIp, sourcePort, expectedSourceIp, expectedProbeToken,
        )
    ) MdnsProbeResult.VISIBLE else MdnsProbeResult.INCONCLUSIVE
}

internal fun mdnsQuery(qualifiedName: String, queryId: Int = 0): ByteArray {
    require(queryId in 0..0xffff)
    val labels = qualifiedName.trimEnd('.').split('.')
    require(labels.isNotEmpty())
    val encoded = labels.map { label ->
        label.toByteArray(StandardCharsets.UTF_8).also { require(it.isNotEmpty() && it.size <= 63) }
    }
    val result = ByteArray(12 + encoded.sumOf { it.size + 1 } + 1 + 4)
    result[0] = (queryId ushr 8).toByte()
    result[1] = queryId.toByte()
    var offset = 12 // zero flags/counts except QDCOUNT
    result[5] = 1 // QDCOUNT = 1
    encoded.forEach { label ->
        result[offset++] = label.size.toByte()
        label.copyInto(result, offset)
        offset += label.size
    }
    result[offset++] = 0
    result[offset++] = 0
    result[offset++] = 255.toByte() // QTYPE ANY
    result[offset++] = 0x80.toByte()
    result[offset] = 1 // QCLASS IN + QU (unicast response requested)
    return result
}

internal fun mdnsResponseAnswersQuery(
    packet: ByteArray,
    length: Int,
    queryId: Int,
    qualifiedName: String,
    sourceIp: String? = null,
    sourcePort: Int? = null,
    expectedSourceIp: String? = null,
    expectedProbeToken: String? = null,
): Boolean {
    if (expectedSourceIp != null && (sourceIp != expectedSourceIp || sourcePort != MDNS_PORT)) return false
    if (length < 12 || length > packet.size) return false
    val actualId = ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
    if (actualId != queryId) return false
    val flags = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
    if (flags and 0x8000 == 0) return false // ignore our looped-back query
    val questions = unsignedShort(packet, 4)
    val answers = unsignedShort(packet, 6)
    val authorities = unsignedShort(packet, 8)
    val additionals = unsignedShort(packet, 10)
    if (questions <= 0 || answers <= 0) return false
    var offset = 12
    var exactQuestion = false
    repeat(questions) {
        val decoded = decodeDnsName(packet, length, offset) ?: return false
        offset = decoded.nextOffset
        if (offset + 4 > length) return false
        val type = unsignedShort(packet, offset)
        val dnsClass = unsignedShort(packet, offset + 2) and 0x7fff
        offset += 4
        if (decoded.name.equals(qualifiedName.trimEnd('.'), ignoreCase = true) && type == 255 && dnsClass == 1) {
            exactQuestion = true
        }
    }
    if (!exactQuestion) return false
    var exactAnswer = false
    var exactProbeToken = expectedProbeToken == null
    repeat(answers + authorities + additionals) {
        val decoded = decodeDnsName(packet, length, offset) ?: return false
        offset = decoded.nextOffset
        if (offset + 10 > length) return false
        val type = unsignedShort(packet, offset)
        val dnsClass = unsignedShort(packet, offset + 2) and 0x7fff
        val dataLength = unsignedShort(packet, offset + 8)
        offset += 10
        if (offset + dataLength > length) return false
        val exactOwner = decoded.name.equals(qualifiedName.trimEnd('.'), ignoreCase = true)
        if (exactOwner && type in setOf(16, 33) && dnsClass == 1) exactAnswer = true
        if (exactOwner && type == 16 && dnsClass == 1 && expectedProbeToken != null &&
            txtContains(packet, offset, dataLength, "probe=$expectedProbeToken")
        ) exactProbeToken = true
        offset += dataLength
    }
    return exactAnswer && exactProbeToken
}

private fun txtContains(packet: ByteArray, offset: Int, length: Int, expected: String): Boolean {
    var cursor = offset
    val end = offset + length
    while (cursor < end) {
        val size = packet[cursor++].toInt() and 0xff
        if (cursor + size > end) return false
        if (String(packet, cursor, size, StandardCharsets.UTF_8) == expected) return true
        cursor += size
    }
    return false
}

private data class DecodedDnsName(val name: String, val nextOffset: Int)

private fun decodeDnsName(packet: ByteArray, length: Int, start: Int): DecodedDnsName? {
    if (start !in 0 until length) return null
    val labels = mutableListOf<String>()
    val visited = BooleanArray(length)
    var offset = start
    var nextOffset = -1
    var jumps = 0
    while (true) {
        if (offset !in 0 until length || visited[offset]) return null
        visited[offset] = true
        val size = packet[offset].toInt() and 0xff
        when {
            size == 0 -> {
                if (nextOffset < 0) nextOffset = offset + 1
                return DecodedDnsName(labels.joinToString("."), nextOffset)
            }
            size and 0xc0 == 0xc0 -> {
                if (offset + 1 >= length || ++jumps > MAX_NAME_JUMPS) return null
                val pointer = ((size and 0x3f) shl 8) or (packet[offset + 1].toInt() and 0xff)
                if (nextOffset < 0) nextOffset = offset + 2
                offset = pointer
            }
            size > 63 || offset + 1 + size > length -> return null
            else -> {
                val label = String(packet, offset + 1, size, StandardCharsets.UTF_8)
                if (label.any { it.code < 0x20 }) return null
                labels += label
                offset += size + 1
            }
        }
    }
}

/** The mDNS group for the responder's address family: 224.0.0.251, or ff02::fb for IPv6. */
internal fun mdnsGroupFor(local: InetAddress): String =
    if (local is Inet6Address) MDNS_GROUP_V6 else MDNS_GROUP

private fun unsignedShort(packet: ByteArray, offset: Int): Int =
    ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

private const val MDNS_GROUP = "224.0.0.251"
private const val MDNS_GROUP_V6 = "ff02::fb"
private const val MDNS_PORT = 5353
private const val MAX_PACKET = 9_000
private const val MAX_NAME_JUMPS = 16
