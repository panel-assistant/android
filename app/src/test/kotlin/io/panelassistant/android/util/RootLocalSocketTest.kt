package io.panelassistant.android.util

import android.net.Credentials
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class RootLocalSocketTest {
    @Test fun onlyRootMayServePrivilegedProtocols() {
        assertTrue(trustedRootSocketPeer(0))
        for (uid in listOf(1000, 2000, 10_123)) assertFalse(trustedRootSocketPeer(uid))
    }

    @Test fun fdCreationAndRemainingSendTimeoutPrecedeConnectAndPeerAdmission() {
        var now = 0L
        val socket = Socket().apply { createFd = { now += 200_000_000L } }
        assertSame(socket, openRootAbstractSocket("helper", MonotonicDeadline(500) { now }) { socket })
        assertEquals(listOf("fd", "timeout=300", "connect", "peer"), socket.events)
        assertFalse(socket.closed)
    }

    @Test fun expiredThrowingAndLateConnectCloseBeforeAnyPeerOrProtocolAccess() {
        for (mode in 0..2) {
            var now = 0L
            val socket = Socket().apply {
                if (mode == 0) createFd = { now += 500_000_000L }
                onConnect = { if (mode == 1) throw SocketTimeoutException() else now += 500_000_000L }
            }
            assertThrows(SocketTimeoutException::class.java) { openRootAbstractSocket("helper", MonotonicDeadline(500) { now }) { socket } }
            assertTrue(socket.closed); assertFalse(socket.events.contains("peer"))
            assertEquals(mode != 0, socket.events.contains("connect"))
        }
    }

    @Test fun nonRootPeerIsRejectedAndClosedBeforeProtocolBytes() {
        val socket = Socket().apply { uid = 2000 }
        assertThrows(SecurityException::class.java) { openRootAbstractSocket("helper", MonotonicDeadline(500) { 0L }) { socket } }
        assertEquals(listOf("fd", "timeout=500", "connect", "peer", "close"), socket.events)
        assertTrue(socket.closed)
    }

    private class Socket : LocalSocket() {
        val events = mutableListOf<String>()
        var createFd: () -> Unit = {}
        var onConnect: () -> Unit = {}
        var uid = 0
        var closed = false
        override fun getInputStream(): InputStream { events += "fd"; createFd(); return ByteArrayInputStream(byteArrayOf()) }
        override fun setSoTimeout(timeout: Int) { events += "timeout=$timeout" }
        override fun connect(endpoint: LocalSocketAddress) { events += "connect"; onConnect() }
        override fun getPeerCredentials(): Credentials {
            events += "peer"
            return object : Credentials(0, uid, 0) { override fun getUid() = this@Socket.uid }
        }
        override fun getOutputStream(): java.io.OutputStream = error("protocol access before admission")
        override fun close() { closed = true; events += "close" }
    }
}
