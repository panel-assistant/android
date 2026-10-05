package io.panelassistant.android.sensors

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * One burst of layer-3 echoes, or null when this platform will not let us send them.
 *
 * The seam exists so the schedule and the classifier can be driven by deterministic tests, and so a
 * root-helper implementation — which would emit and time the packets itself and return the round
 * trips through its own API — can be slotted in later without touching anything above it.
 */
internal interface PathEchoSource {
    /**
     * Send [echoes] echo requests to [target] and return what came back.
     *
     * Returns null ONLY when the platform refuses to provide the capability at all, which the caller
     * must treat as "this panel cannot measure layer 3" rather than as a fault in the network. Any
     * other outcome — including every echo being lost — is a [PathBurst] describing what happened.
     */
    fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long): PathBurst?
}

/**
 * Layer-3 echoes from the app's own uid, with no root, no subprocess and no second TCP connection.
 *
 * Linux grants unprivileged ICMP datagram sockets to any GID inside `net.ipv4.ping_group_range`,
 * which Android sets wide open; this is the same mechanism the platform's own `ping` binary uses,
 * and that binary is not setuid. What the kernel permits is not the whole story, though: an OEM
 * SELinux policy can still deny `untrusted_app` the `icmp_socket` class, and that denial arrives as
 * an [ErrnoException] from [Os.socket]. It is reported as "unsupported" and never as packet loss —
 * the panel simply keeps the verdict it can derive from its WebSocket instead.
 *
 * Three details of the unprivileged ping socket differ from the raw-socket code most examples show,
 * and each one silently breaks matching if missed:
 *  - the kernel REWRITES the echo identifier to the socket's own port, so replies are matched on the
 *    sequence number and a per-burst random token in the payload, never on the id;
 *  - a received datagram begins at the ICMP header, with no IP header in front of it;
 *  - the socket is connected first, so only the target's replies are delivered to it.
 */
internal class IcmpEchoSource : PathEchoSource {
    private val random = SecureRandom()

    override fun burst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long): PathBurst? =
        try {
            attemptBurst(target, echoes, perEchoTimeoutMs, nowMs)
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            // Cooperative cancellation is not a platform refusal and must keep propagating.
            throw cancellation
        } catch (_: Throwable) {
            // EVERY other failure of the complete operation means the same thing to the panel: this
            // platform will not let it measure layer 3. The boundary is around the WHOLE operation
            // rather than the socket call alone, because a refusal peculiar to one OEM can surface
            // from connect, poll, read or a missing method just as easily as from socket(), and an
            // escaping throwable here would reach a coroutine that can take the process down. A
            // diagnostic must never be able to crash the app it is diagnosing.
            null
        }

    private fun attemptBurst(target: InetAddress, echoes: Int, perEchoTimeoutMs: Long, nowMs: () -> Long): PathBurst? {
        val v6 = target is Inet6Address
        val fd: FileDescriptor = try {
            Os.socket(
                if (v6) OsConstants.AF_INET6 else OsConstants.AF_INET,
                OsConstants.SOCK_DGRAM,
                if (v6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP,
            )
        } catch (_: ErrnoException) {
            return null
        } catch (_: SecurityException) {
            return null
        }

        var received = 0
        val rtts = ArrayList<Long>(echoes)
        val token = random.nextInt()
        try {
            try {
                Os.connect(fd, target, 0)
            } catch (_: ErrnoException) {
                // No route at all: a real path failure, and a burst that lost everything says so.
                return PathBurst(atMs = nowMs(), sent = echoes, received = 0, rttsMs = emptyList())
            }
            for (seq in 1..echoes) {
                if (sendOne(fd, v6, seq, token)) {
                    val sentAt = nowMs()
                    val rtt = awaitReply(fd, v6, seq, token, perEchoTimeoutMs, sentAt, nowMs)
                    if (rtt >= 0L) {
                        received++
                        rtts.add(rtt)
                    }
                }
            }
        } finally {
            runCatching { Os.close(fd) }
        }
        return PathBurst(atMs = nowMs(), sent = echoes, received = received, rttsMs = rtts)
    }

    /** True when the request went out; a send error is an unanswered echo, not a thrown burst. */
    private fun sendOne(fd: FileDescriptor, v6: Boolean, seq: Int, token: Int): Boolean {
        val packet = IcmpEchoPacket.request(v6, seq, token)
        return try {
            Os.write(fd, ByteBuffer.wrap(packet)) > 0
        } catch (_: ErrnoException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }

    /**
     * Wait for the reply to [seq], returning its round trip or `-1`.
     *
     * Replies for an ABANDONED earlier echo can arrive here; they are read, ignored and the wait
     * continues on the remaining budget, because attributing a late reply to the wrong sequence would
     * report a round trip that never happened.
     */
    private fun awaitReply(
        fd: FileDescriptor,
        v6: Boolean,
        seq: Int,
        token: Int,
        timeoutMs: Long,
        sentAtMs: Long,
        nowMs: () -> Long,
    ): Long {
        val deadline = sentAtMs + timeoutMs
        val buffer = ByteBuffer.allocate(MAX_DATAGRAM)
        while (true) {
            val remaining = deadline - nowMs()
            if (remaining <= 0L) return -1L
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } catch (_: ErrnoException) {
                return -1L
            }
            if (ready <= 0) return -1L
            buffer.clear()
            val read = try {
                Os.read(fd, buffer)
            } catch (_: ErrnoException) {
                // An ICMP error (host or network unreachable) is delivered to the socket as an error
                // on receive. The echo is lost, which is exactly what the burst should record.
                return -1L
            } catch (_: java.io.IOException) {
                return -1L
            }
            val at = nowMs()
            if (read <= 0) continue
            // The datagram starts at the ICMP header: no IP header is present on a ping socket.
            if (IcmpEchoPacket.matches(buffer.array(), read, v6, seq, token)) return (at - sentAtMs).coerceAtLeast(0L)
        }
    }

    private companion object {
        const val MAX_DATAGRAM = 1500
    }
}

/**
 * The wire format, kept apart from the syscalls so it can be proved without a device.
 *
 * This is where every subtlety of the unprivileged ping socket lives, and each one is silent when
 * wrong: a reply is matched on sequence and an echoed token because the kernel REWRITES the
 * identifier; a received datagram is parsed from the ICMP header because there is no IP header in
 * front of it on a datagram socket; and the checksum is computed over the whole packet with its own
 * field zeroed. A unit test can hold all of that to account; only the sending needs hardware.
 */
internal object IcmpEchoPacket {
    const val HEADER_BYTES = 8
    const val PAYLOAD_BYTES = 16
    const val ICMP_ECHO_REQUEST = 8
    const val ICMP_ECHO_REPLY = 0
    const val ICMPV6_ECHO_REQUEST = 128
    const val ICMPV6_ECHO_REPLY = 129

    /** Build an echo request carrying [seq] and [token]. */
    fun request(v6: Boolean, seq: Int, token: Int): ByteArray {
        val packet = ByteArray(HEADER_BYTES + PAYLOAD_BYTES)
        packet[0] = (if (v6) ICMPV6_ECHO_REQUEST else ICMP_ECHO_REQUEST).toByte()
        packet[1] = 0
        packet[2] = 0 // checksum, filled below for IPv4; the kernel owns it for ICMPv6
        packet[3] = 0
        packet[4] = 0 // identifier: the kernel overwrites this with the socket's port
        packet[5] = 0
        packet[6] = ((seq shr 8) and 0xFF).toByte()
        packet[7] = (seq and 0xFF).toByte()
        java.nio.ByteBuffer.wrap(packet, HEADER_BYTES, 4).putInt(token)
        if (!v6) {
            val sum = checksum(packet)
            packet[2] = ((sum shr 8) and 0xFF).toByte()
            packet[3] = (sum and 0xFF).toByte()
        }
        return packet
    }

    /** Whether [bytes] is the reply to [seq] carrying [token]. The identifier is never compared. */
    fun matches(bytes: ByteArray, length: Int, v6: Boolean, seq: Int, token: Int): Boolean {
        if (length < HEADER_BYTES + 4) return false
        val expectedType = if (v6) ICMPV6_ECHO_REPLY else ICMP_ECHO_REPLY
        if ((bytes[0].toInt() and 0xFF) != expectedType) return false
        val replySeq = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        if (replySeq != (seq and 0xFFFF)) return false
        return java.nio.ByteBuffer.wrap(bytes, HEADER_BYTES, 4).int == token
    }

    /** Standard internet checksum. The kernel recomputes it, but a correct one costs nothing. */
    fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < bytes.size) {
            sum += ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < bytes.size) sum += (bytes[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
