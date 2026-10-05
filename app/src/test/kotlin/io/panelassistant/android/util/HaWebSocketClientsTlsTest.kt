package io.panelassistant.android.util

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.close
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * TLS identity contract of the shared HA WebSocket factory. The factory never dials literal
 * addresses in production — the URL keeps the configured hostname — so certificate hostname
 * verification must be live platform behavior, and [HaWebSocketClients.TlsTrust] must supply trust
 * material only, never bypass verification. The mismatch test is the pin that keeps a future
 * "accept anything" override from landing silently.
 *
 * The server key is a throwaway self-signed certificate for `localhost` generated per test run by
 * the JDK's own keytool; nothing here is a secret.
 */
class HaWebSocketClientsTlsTest {

    companion object {
        private lateinit var keyStoreFile: File
        private const val STORE_PASS = "throwaway-test-store"
        private var keytoolAvailable = false

        @JvmStatic
        @BeforeClass
        fun generateThrowawayCertificate() {
            keyStoreFile = File.createTempFile("ha-ws-tls-test", ".p12").apply { deleteOnExit() }
            keyStoreFile.delete()
            val keytool = File(System.getProperty("java.home"), "bin/keytool")
            if (!keytool.canExecute()) return
            val process = ProcessBuilder(
                keytool.absolutePath, "-genkeypair", "-alias", "test",
                "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost",
                "-validity", "2",
                "-keystore", keyStoreFile.absolutePath,
                "-storetype", "PKCS12", "-storepass", STORE_PASS,
            ).redirectErrorStream(true).start()
            keytoolAvailable = process.waitFor() == 0 && keyStoreFile.length() > 0
        }
    }

    private class TlsMaterial {
        val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
            keyStoreFile.inputStream().use { load(it, STORE_PASS.toCharArray()) }
        }
        val serverContext: SSLContext = SSLContext.getInstance("TLS").apply {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, STORE_PASS.toCharArray())
            init(kmf.keyManagers, null, null)
        }
        val clientTrust: HaWebSocketClients.TlsTrust = run {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            HaWebSocketClients.TlsTrust(context.socketFactory, trustManager)
        }
    }

    /** One-shot TLS WebSocket responder: completes the handshake, answers the 101 upgrade. */
    private fun startTlsResponder(material: TlsMaterial): SSLServerSocket {
        val server = material.serverContext.serverSocketFactory
            .createServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread {
                        runCatching {
                            val input = socket.getInputStream()
                            val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) {
                                val byte = input.read()
                                if (byte < 0) return@runCatching
                                header.append(byte.toChar())
                            }
                            val key = Regex("Sec-WebSocket-Key: (\\S+)", RegexOption.IGNORE_CASE)
                                .find(header)?.groupValues?.get(1) ?: return@runCatching
                            val accept = Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-1")
                                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                            )
                            socket.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                                )
                                flush()
                            }
                            while (input.read() >= 0) Unit
                        }
                        runCatching { socket.close() }
                    }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun aHostnameMismatchIsRejectedBeforeTheUpgrade() {
        assumeTrue("keytool unavailable in this environment", keytoolAvailable)
        val material = TlsMaterial()
        val server = startTlsResponder(material)
        // The certificate names only `localhost`; dialing the same listener as `127.0.0.1` must
        // fail hostname verification even though the chain itself is fully trusted.
        val client = HaWebSocketClients.client(tls = material.clientTrust)
        try {
            runBlocking {
                try {
                    withTimeout(15_000) {
                        client.webSocketSession("wss://127.0.0.1:${server.localPort}/api/websocket")
                    }
                    fail("hostname verification accepted a certificate for a different name")
                } catch (expected: Exception) {
                    val chain = generateSequence<Throwable>(expected) { it.cause }.toList()
                    assertTrue(
                        "failure is TLS identity, was: $chain",
                        chain.any { it is SSLPeerUnverifiedException } ||
                            chain.any { it.message.orEmpty().contains("verif", ignoreCase = true) } ||
                            chain.any { it.message.orEmpty().contains("Hostname", ignoreCase = true) },
                    )
                }
            }
        } finally {
            client.close()
            server.close()
        }
    }

    @Test fun aMatchingHostnameCompletesTheTlsUpgrade() {
        assumeTrue("keytool unavailable in this environment", keytoolAvailable)
        val material = TlsMaterial()
        val server = startTlsResponder(material)
        val client = HaWebSocketClients.client(
            tls = material.clientTrust,
            resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
        )
        try {
            runBlocking {
                val session = withTimeout(15_000) {
                    client.webSocketSession("wss://localhost:${server.localPort}/api/websocket")
                }
                session.close()
            }
        } finally {
            client.close()
            server.close()
        }
    }
}
