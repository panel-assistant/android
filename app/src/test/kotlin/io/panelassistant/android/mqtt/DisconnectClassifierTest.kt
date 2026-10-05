package io.panelassistant.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectClassifierTest {
    @Test fun `Netty no-route exception is a network failure`() {
        assertEquals(
            "unreachable",
            classifyDisconnect("io.netty.channel.AbstractChannel\$AnnotatedNoRouteToHostException"),
        )
        assertEquals("unreachable", classifyDisconnect("java.net.UnknownHostException"))
    }


    @Test fun authFailures() {
        assertEquals("auth-failed", classifyDisconnect("CONNACK reason NOT_AUTHORIZED"))
        assertEquals("auth-failed", classifyDisconnect("BAD_USER_NAME_OR_PASSWORD"))
        assertEquals("auth-failed", classifyDisconnect("server BANNED the client"))
        assertEquals("auth-failed", classifyDisconnect("AUTHENTICATION method mismatch"))
    }

    @Test fun networkFailures() {
        assertEquals("unreachable", classifyDisconnect("Connection refused"))
        assertEquals("unreachable", classifyDisconnect("host UNREACHABLE"))
        assertEquals("unreachable", classifyDisconnect("Connection reset by peer"))
        assertEquals("unreachable", classifyDisconnect("No route to host"))
    }

    @Test fun unknownOrEmptyIsPlainDisconnected() {
        assertEquals("disconnected", classifyDisconnect(null))
        assertEquals("disconnected", classifyDisconnect(""))
        assertEquals("disconnected", classifyDisconnect("session taken over"))
    }

    @Test fun authIsCheckedBeforeNetwork() {
        // message mentions both an auth reason and "connection" (network) — auth wins
        assertEquals("auth-failed", classifyDisconnect("NOT_AUTHORIZED on this connection"))
    }
}
