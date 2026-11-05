package io.panelassistant.android

import android.net.ConnectivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated runner clears both packages and starts a fresh process for EACH method. */
@RunWith(AndroidJUnit4::class)
class FirstRunSignInInstrumentedTest {
    @Test fun urlWithoutTokenShowsPhysicalSignIn() = checkSignIn(learning = false)

    @Test fun entityLearningWithoutTokenStillShowsPhysicalSignIn() = checkSignIn(learning = true)

    @Test fun configuredTokenWaitsForTheUnansweredSetupQuestion() {
        val (device, config) = freshConfig()
        assertTrue(config.setHaConnection("http://127.0.0.1:18763", "fixture-token"))
        config.setupIdentityConfirmed = true
        config.setupEntityFilterAnswered = false
        config.setupEverCompleted = false
        config.setDashboardEntityLearningEnabled(false)
        assertTrue(config.builtInRendererReady())
        ActivityScenario.launch(DashboardActivity::class.java).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertTrue(
                "A credentialed panel must keep its real setup-question surface",
                device.wait(Until.hasObject(By.text(context.getString(R.string.notice_line_setup_question))), 30_000),
            )
            assertFalse(device.hasObject(By.clazz("android.webkit.WebView")))
        }
    }

    private fun checkSignIn(learning: Boolean) {
        val (device, config) = freshConfig()
        SignInServer().use { server ->
            assertTrue(config.setHaConnection(server.url, ""))
            config.setHaRefreshToken("")
            config.setupIdentityConfirmed = true
            config.setupEntityFilterAnswered = false
            config.setupEverCompleted = false
            config.setDashboardEntityLearningEnabled(learning)
            assertFalse("The regression input must be before renderer readiness", config.builtInRendererReady())
            assertFalse(config.setupEverCompleted)
            assertFalse(config.setupEntityFilterAnswered)
            ActivityScenario.launch(DashboardActivity::class.java).use {
                assertTrue(
                    "The real Activity must display the local HA authorization page before any entity hold",
                    device.wait(Until.hasObject(By.text(SignInServer.MARKER)), 30_000),
                )
                assertTrue(device.hasObject(By.clazz("android.webkit.WebView")))
                assertTrue("The visible page must have come through HA authorization", server.authorizations.get() > 0)
                assertNull("The fake HA listener must not fail silently", server.failure.get())
                assertEquals("", config.haToken)
                assertEquals("", config.haRefreshToken)
            }
        }
    }

    private fun freshConfig(): Pair<UiDevice, Config> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(
            "This fixture requires a disposable emulator",
            listOf("ro.kernel.qemu", "ro.boot.qemu").any {
                device.executeShellCommand("getprop $it").trim() == "1"
            },
        )
        val context = instrumentation.targetContext
        assertTrue(
            "Dashboard network admission requires an active emulator network",
            context.getSystemService(ConnectivityManager::class.java).activeNetwork != null,
        )
        val config = Config(context)
        assertEquals("Runner must clear app data before every method", "", config.haUrl)
        assertEquals("", config.haToken)
        assertEquals("", config.haRefreshToken)
        assertFalse(config.setupEverCompleted)
        return device to config
    }

    private class SignInServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val authorizations = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "first-run-ha") {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        client.soTimeout = 5_000
                        val input = client.getInputStream().bufferedReader()
                        val request = requireNotNull(input.readLine())
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val authorization = request.startsWith("GET /auth/authorize?")
                        if (authorization) authorizations.incrementAndGet()
                        val body = if (authorization) {
                            "<!doctype html><html><body><h1>$MARKER</h1><label>User<input></label></body></html>"
                        } else ""
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val status = if (authorization) "200 OK" else "404 Not Found"
                        client.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !socket.isClosed) failure.set(error)
            }
        }

        override fun close() {
            socket.close()
            worker.join(6_000)
            assertFalse("The local HA listener must stop", worker.isAlive)
            assertNull("The local HA listener must finish without unexpected errors", failure.get())
        }

        companion object {
            const val MARKER = "First run HA authorization fixture"
        }
    }
}
