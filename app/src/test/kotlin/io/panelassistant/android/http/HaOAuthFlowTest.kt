package io.panelassistant.android.http

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.HaOAuthAttemptAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class HaOAuthFlowTest {
    private val owner = HaAuthOwner("", "", "", "")
    private fun authority(epoch: Long = 1L) = HaOAuthAttemptAuthority(owner, epoch)

    @Test fun `start binds an encoded authorize URL to the panel callback`() {
        val state = "a".repeat(43)
        val flow = HaOAuthFlow(nowMillis = { 0L }, stateToken = { state })
        val started = flow.start("https://ha.example/ha", "http://panel.local:8888", authority())
        val query = URI(started.authorizationUrl).rawQuery.split('&').associate { field ->
            val (key, value) = field.split('=', limit = 2)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

        assertEquals("https://ha.example/ha/auth/authorize", started.authorizationUrl.substringBefore('?'))
        assertEquals("http://panel.local:8888/", query["client_id"])
        assertEquals("http://panel.local:8888$HA_OAUTH_CALLBACK_PATH", query["redirect_uri"])
        assertEquals("code", query["response_type"])
        assertEquals(state, query["state"])
        val claimed = flow.claim(state, "http://panel.local:8888") as HaOAuthClaim.Claimed
        assertEquals("http://panel.local:8888/", claimed.attempt.clientId)
        assertEquals(owner, claimed.attempt.expectedOwner)
        assertEquals(1L, claimed.attempt.expectedEpoch)
    }

    @Test fun `state is one use and exact-origin bound`() {
        val state = "b".repeat(43)
        val flow = HaOAuthFlow(nowMillis = { 0L }, stateToken = { state })
        flow.start("http://ha.local:8123", "http://192.168.1.20:8888", authority())

        assertTrue(flow.claim(state, "http://panel.local:8888") is HaOAuthClaim.WrongOrigin)
        assertTrue(flow.claim(state, "http://192.168.1.20:8888") is HaOAuthClaim.Invalid)
    }

    @Test fun `newest attempt invalidates its predecessor and monotonic expiry removes it`() {
        var now = 0L
        var next = 0
        val tokens = listOf("c".repeat(43), "d".repeat(43))
        val flow = HaOAuthFlow(nowMillis = { now }, stateToken = { tokens[next++] }, ttlMillis = 1_000)
        flow.start("http://ha-a", "http://panel:8888", authority(1))
        flow.start("http://ha-b", "http://panel:8888", authority(2))
        assertTrue(flow.claim(tokens[0], "http://panel:8888") is HaOAuthClaim.Invalid)
        assertEquals(1, flow.pendingCount())

        now = 1_000
        assertTrue(flow.claim(tokens[1], "http://panel:8888") is HaOAuthClaim.Invalid)
        assertEquals(0, flow.pendingCount())
    }

    @Test fun `state syntax requires a full url-safe random token`() {
        assertTrue(validHaOAuthState("A0-_" + "z".repeat(39)))
        assertFalse(validHaOAuthState("short"))
        assertFalse(validHaOAuthState("x".repeat(42) + "+"))
        assertFalse(validHaOAuthState("x".repeat(87)))
    }

    @Test fun `panel origin strictly canonicalizes ordinary and ipv6 hosts`() {
        assertEquals("http://panel.local:8888", panelHttpOrigin("Panel.Local:8888", 8888))
        assertEquals("http://192.168.1.5:8888", panelHttpOrigin("192.168.1.5", 8888))
        assertEquals("http://[fe80::1]:8888", panelHttpOrigin("[fe80::1]:8888", 8888))
        assertEquals("http://localhost", panelHttpOrigin("localhost:80", 8888))
        listOf(
            null, "", "user@panel.local:8888", "panel.local/path", "panel.local?x=1",
            "[fe80::1", "panel.local:99999", "panel.local:abc", "panel.local  :8888",
        ).forEach { assertEquals("must reject $it", null, panelHttpOrigin(it, 8888)) }
    }
}
