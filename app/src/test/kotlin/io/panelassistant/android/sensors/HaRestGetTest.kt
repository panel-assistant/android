package io.panelassistant.android.sensors

import io.panelassistant.android.util.ByteLimitExceeded
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The one Home Assistant REST GET shared by the ambient and presence transports. */
class HaRestGetTest {
    private class StubConnection(private val status: Int, private val body: String = "[]") :
        HttpURLConnection(URL("https://stub.invalid/")) {
        val headers = mutableMapOf<String, String>()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getInputStream(): InputStream {
            if (status >= 300) throw FileNotFoundException("HTTP $status")
            return ByteArrayInputStream(body.toByteArray())
        }
        override fun getErrorStream(): InputStream? = null
    }

    private fun get(connection: StubConnection, missingIsNull: Boolean = false, maxBytes: Long = 1024L) = runBlocking {
        haRestGet("https://ha.invalid/", "token", "/api/states", maxBytes, 1_000, 1_000, missingIsNull) { url ->
            assertEquals("https://ha.invalid/api/states", url.toString())
            connection
        }
    }

    @Test fun `a 2xx answer is the body, read with the bearer token and no redirect following`() {
        val connection = StubConnection(200, "[1]")
        assertEquals("[1]", get(connection))
        assertEquals("Bearer token", connection.headers["Authorization"])
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test fun `401 and 403 are authentication failures`() {
        for (status in listOf(401, 403)) {
            try {
                get(StubConnection(status))
                fail("HTTP $status was accepted")
            } catch (_: HaAuthenticationException) {
            }
        }
    }

    @Test fun `a redirect or other non-2xx is a protocol failure, and 404 is null only when absence is an answer`() {
        assertNull(get(StubConnection(404), missingIsNull = true))
        for (status in listOf(302, 404, 503)) {
            try {
                get(StubConnection(status))
                fail("HTTP $status was accepted")
            } catch (expected: HaProtocolException) {
                assertEquals("Home Assistant REST request failed (HTTP $status)", expected.message)
            }
        }
    }

    @Test fun `a body above the byte bound is refused`() {
        try {
            get(StubConnection(200, "x".repeat(65)), maxBytes = 64L)
            fail("an oversized body was returned")
        } catch (_: ByteLimitExceeded) {
        }
    }
}
