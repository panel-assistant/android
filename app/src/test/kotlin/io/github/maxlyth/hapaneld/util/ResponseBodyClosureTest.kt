package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.HttpAudioTransfer
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.sensors.HaProtocolException
import io.github.maxlyth.hapaneld.sensors.KtorHaAmbientTransport
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The platform HTTP stack keeps a pooled connection allocated until the response body is closed,
 * and `disconnect()` does not do that for it: an unread error body is what logs as
 * `A connection to <url> was leaked`. The JDK's own connection frees the socket on `disconnect()`, so
 * a real socket cannot show the difference; this stub behaves like the platform one, handing out a body
 * stream that counts as leaked until it is closed, through the connection seam production uses.
 */
class ResponseBodyClosureTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Answers [status]; an error status refuses `inputStream` and serves its body on `errorStream`. */
    private class AnsweringConnection(
        private val status: Int,
        private val location: String? = null,
        private val declaredLength: Long = -1L,
    ) : HttpURLConnection(URL("https://stub.invalid/")) {
        /** Exists from the moment the response is answered, as the platform's pooled body does. */
        private val body = TrackedStream()

        val unclosedBodies: Int get() = if (body.closed) 0 else 1

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status
        override fun getContentLengthLong(): Long = declaredLength
        override fun getHeaderField(name: String?): String? = if (name == "Location") location else null

        override fun getInputStream(): InputStream {
            if (status >= 400) throw FileNotFoundException("HTTP $status")
            return body
        }

        override fun getErrorStream(): InputStream? = if (status >= 400) body else null
    }

    private class TrackedStream : ByteArrayInputStream("""{"message":"unavailable"}""".toByteArray()) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test fun updateDownloadClosesTheBodyOfARefusalAndOfARedirect() {
        for ((status, location) in listOf(404 to null, 503 to null, 302 to "http://downgrade.invalid/app.apk")) {
            val connection = AnsweringConnection(status, location)
            val result = AppInstaller.download(
                "https://cdn.invalid/app.apk", File(temporary.root, "app-$status.apk"), 1024L,
                openConnection = { connection },
            )
            assertEquals("HTTP $status", AppInstaller.DownloadResult.Failed, result)
            assertEquals("HTTP $status body left open", 0, connection.unclosedBodies)
        }
    }

    @Test fun updateDownloadClosesTheBodyItRefusesAsTooLarge() {
        val connection = AnsweringConnection(200, declaredLength = 4096L)
        val result = AppInstaller.download(
            "https://cdn.invalid/app.apk", File(temporary.root, "big.apk"), 1024L,
            openConnection = { connection },
        )
        assertEquals(AppInstaller.DownloadResult.TooLarge, result)
        assertEquals(0, connection.unclosedBodies)
    }

    @Test fun audioTransferClosesTheBodyOfARefusalAndOfAnOversizedAnswer() {
        val refused = AnsweringConnection(404)
        try {
            HttpAudioTransfer(4L, openConnection = { refused }).download("http://stub.invalid/audio", temporary.newFile("refused"))
            fail("a 404 must fail the transfer")
        } catch (expected: FileNotFoundException) {
            assertEquals(0, refused.unclosedBodies)
        }
        val oversized = AnsweringConnection(200, declaredLength = 5L)
        try {
            HttpAudioTransfer(4L, openConnection = { oversized }).download("http://stub.invalid/audio", temporary.newFile("big"))
            fail("an oversized answer must fail the transfer")
        } catch (expected: ByteLimitExceeded) {
            assertEquals(0, oversized.unclosedBodies)
        }
    }

    @Test fun homeAssistantRestReadClosesTheBodyOnEveryRefusal() = runBlocking {
        for (status in listOf(401, 404, 503)) {
            val connection = AnsweringConnection(status)
            val transport = KtorHaAmbientTransport(openConnection = { connection })
            try {
                transport.state("https://ha.invalid", "token", "light.kitchen")
                if (status != 404) fail("HTTP $status must fail the read")
            } catch (expected: HaAuthenticationException) {
                assertEquals(401, status)
            } catch (expected: HaProtocolException) {
                assertTrue(status == 503)
            }
            assertEquals("HTTP $status body left open", 0, connection.unclosedBodies)
        }
    }
}
