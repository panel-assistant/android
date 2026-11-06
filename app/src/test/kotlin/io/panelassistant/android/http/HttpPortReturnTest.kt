package io.panelassistant.android.http

import android.content.SharedPreferences
import io.panelassistant.android.Config
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A panel that fell back off the default port can be asked to return to it at the next HTTP start. */
class HttpPortReturnTest {
    @Test fun aPanelOnItsFallbackPortReturnsToTheDefaultAtTheNextStartWhenAsked() =
        PaneldListenerPortTest.withDefaultPortLock {
            withFullReadServer { config, fixture ->
                val server = fixture.server
                prepare(server, config)
                val fallback = holdingDefault { startedPort(server) }
                assertNotEquals(Config.DEFAULT_PORT, fallback)
                assertEquals(fallback, config.httpPort)

                try {
                    val (status, body) = postPort(fallback, "8888")
                    assertEquals(body, 200, status)
                    assertTrue(body, JSONObject(body).getJSONArray("applied").toString().contains("http_port"))
                    assertEquals("The running listener keeps answering where it is", "ok", answer(fallback))
                    assertEquals("Nothing reads the new port before it answers", fallback, config.httpPort)

                    assertEquals(Config.DEFAULT_PORT, restart(server))
                    assertEquals(Config.DEFAULT_PORT, config.httpPort)
                    assertEquals("ok", answer(Config.DEFAULT_PORT))
                    assertNull("The request is consumed by the start", config.httpPortRequest)
                } finally { stopListener(server) }
            }
        }

    @Test fun aRequestedDefaultThatIsBusyAtTheNextStartFallsBackAndIsConsumed() =
        PaneldListenerPortTest.withDefaultPortLock {
            withFullReadServer { config, fixture ->
                val server = fixture.server
                prepare(server, config)
                holdingDefault {
                    val fallback = startedPort(server)
                    try {
                        val (status, body) = postPort(fallback, "8888")
                        assertEquals(body, 200, status)
                        val next = restart(server)
                        assertNotEquals(Config.DEFAULT_PORT, next)
                        assertEquals(next, config.httpPort)
                        assertEquals("ok", answer(next))
                        assertNull("A start that could not get the default still consumes the request", config.httpPortRequest)
                    } finally { stopListener(server) }
                }
            }
        }

    @Test fun onlyTheDefaultPortCanBeAskedFor() =
        PaneldListenerPortTest.withDefaultPortLock {
            withFullReadServer { config, fixture ->
                val server = fixture.server
                prepare(server, config)
                // Kept off the default port so no other test here finds it with lingering connections.
                holdingDefault {
                    val port = startedPort(server)
                    try {
                        val (status, body) = postPort(port, "9999")
                        assertEquals(body, 400, status)
                        assertTrue(body, body.startsWith("http_port: only 8888 can be set"))
                        assertNull(config.httpPortRequest)
                    } finally { stopListener(server) }
                }
            }
        }

    private fun prepare(server: PaneldServer, config: Config) {
        server.field("haArea", HaAreaRuntime(config, server.readField("scope") as kotlinx.coroutines.CoroutineScope, Any()) { false })
        server.field("securityModeChanged", SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> })
    }

    private fun <T> holdingDefault(block: () -> T): T =
        ServerSocket().use { held ->
            held.bind(InetSocketAddress(Config.DEFAULT_PORT))
            block()
        }

    private fun startedPort(server: PaneldServer): Int {
        server.start()
        return (server.readField("listener") as PaneldListener).readField("boundPort") as Int
    }

    /** The service builds a new server per generation; a fresh listener reads the saved port the same way. */
    private fun restart(server: PaneldServer): Int {
        stopListener(server)
        return startedPort(server)
    }

    private fun stopListener(server: PaneldServer) {
        (server.readField("listener") as? PaneldListener)?.stop()
        server.field("listener", null)
    }

    private fun postPort(port: Int, value: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port/api/v1/config").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write("http_port=$value".toByteArray()) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return status to stream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun answer(port: Int): String {
        val connection = URL("http://127.0.0.1:$port/api/v1/config").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 10_000
            assertEquals(200, connection.responseCode)
            val read = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            assertEquals("The answering server reports the port it listens on", port, read.getInt("http_port"))
            return "ok"
        } finally { connection.disconnect() }
    }

    private companion object {
        fun Any.field(name: String, value: Any?) {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
        }
        fun Any.readField(name: String): Any? = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
    }
}
