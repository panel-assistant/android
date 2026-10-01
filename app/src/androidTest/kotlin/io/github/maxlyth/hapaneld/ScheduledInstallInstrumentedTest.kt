package io.github.maxlyth.hapaneld

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.UpdateChecker
import io.github.maxlyth.hapaneld.util.WebViewInstaller
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLStreamHandler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run this class alone in a fresh instrumentation process: URL allows one factory per process. */
@RunWith(AndroidJUnit4::class)
class ScheduledInstallInstrumentedTest {
    @Test fun theRealDailyCheckNeverClaimsAnInstallWithLegacyFlagsEnabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppState.preferences(context, "config", "ha-paneld")
        val original = (LEGACY_VALUES.keys + "http_port").associateWith { preferences.all[it] }
        val port = 18888
        assertFalse("requires a cold service process", health(port).contains(" pkg=${context.packageName}"))
        assertNull("pending authorized WebView recovery requires a separate test", WebViewInstaller.pendingRollback(context))
        assertFalse("another operation owns the install lane", InstallProgress.running)
        val network = CatalogueOnlyNetwork()
        URL.setURLStreamHandlerFactory(network::handler)
        var previousChecked: (() -> Unit)? = null
        var previousProgress: (() -> Unit)? = null
        var checkedObserver: (() -> Unit)? = null
        var progressObserver: (() -> Unit)? = null
        try {
            assertTrue("test HTTP port did not commit", preferences.edit().putInt("http_port", port).commit())
            writeLegacy(preferences)
            PaneldService.start(context)
            val startupDeadline = SystemClock.elapsedRealtime() + 30_000L
            while (!health(port).contains(" pkg=${context.packageName}")) {
                assertTrue("the real service never became healthy", SystemClock.elapsedRealtime() < startupDeadline)
                Thread.sleep(50)
            }
            // Startup migration may remove these keys. Reintroduce them to prove the live periodic
            // owner cannot act on them even if an old writer restores its saved configuration.
            writeLegacy(preferences)
            LEGACY_VALUES.forEach { (key, value) -> assertEquals("legacy fixture: $key", value, preferences.all[key]) }
            val before = InstallProgress.presentationSnapshot().generation
            val completedCheck = CountDownLatch(1)
            val admittedInstall = CountDownLatch(1)
            val admissions = CopyOnWriteArrayList<String>()
            previousChecked = UpdateChecker.onChecked
            previousProgress = InstallProgress.observer
            assertTrue("service catalogue observer was not attached", previousChecked != null)
            checkedObserver = {
                previousChecked?.invoke()
                completedCheck.countDown()
            }
            progressObserver = {
                val snapshot = InstallProgress.presentationSnapshot()
                if (snapshot.running) {
                    admissions += snapshot.component
                    admittedInstall.countDown()
                }
                previousProgress?.invoke()
            }
            UpdateChecker.onChecked = checkedObserver
            InstallProgress.observer = progressObserver
            // Nothing invokes UpdateChecker directly: this must be the service's initial 30s tick.
            assertTrue("the scheduled catalogue check never completed", completedCheck.await(45, TimeUnit.SECONDS))
            assertTrue("the app catalogue was not requested", network.catalogues.any { it.contains("/panel-assistant/android/") })
            assertTrue("the Companion catalogue was not requested", network.catalogues.any { it.contains("/home-assistant/android/") })
            // onChecked fires before the old install steps. Keep observing after it so a zero count
            // cannot pass simply because the assertion raced those steps.
            val installStarted = admittedInstall.await(5, TimeUnit.SECONDS)
            assertFalse("scheduled install admissions: $admissions", installStarted)
            assertEquals("the scheduled check claimed an operation", before, InstallProgress.presentationSnapshot().generation)
            assertTrue("scheduled APK requests: ${network.apks}", network.apks.isEmpty())
        } finally {
            if (UpdateChecker.onChecked === checkedObserver) UpdateChecker.onChecked = previousChecked
            if (InstallProgress.observer === progressObserver) InstallProgress.observer = previousProgress
            network.active = false
            context.stopService(Intent(context, PaneldService::class.java))
            val restore = preferences.edit()
            original.forEach { (key, value) ->
                when (value) {
                    null -> restore.remove(key)
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    is String -> restore.putString(key, value)
                    else -> error("unexpected legacy setting type: $key")
                }
            }
            assertTrue("legacy settings were not restored", restore.commit())
        }
    }

    private fun writeLegacy(preferences: SharedPreferences) {
        val editor = preferences.edit()
        LEGACY_VALUES.forEach { (key, value) ->
            if (value is Boolean) editor.putBoolean(key, value) else editor.putString(key, value as String)
        }
        assertTrue("legacy settings fixture did not commit", editor.commit())
    }

    private fun health(port: Int): String = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 250)
            socket.soTimeout = 500
            socket.getOutputStream().write("GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
    }.getOrDefault("")

    /** Empty catalogues expose the old attempted installs while making APK admission impossible. */
    private class CatalogueOnlyNetwork {
        private val defaults = listOf("http", "https").associateWith { URL("$it://localhost/") }
        @Volatile var active = true
        val catalogues = CopyOnWriteArrayList<String>()
        val apks = CopyOnWriteArrayList<String>()

        fun handler(protocol: String): URLStreamHandler? {
            val original = defaults[protocol] ?: return null
            return object : URLStreamHandler() {
                override fun openConnection(url: URL): HttpURLConnection {
                    // The context URL retains its original handler, restoring normal networking
                    // after the test despite Java's process-wide, one-shot factory registration.
                    if (!active) return URL(original, url.toString()).openConnection() as HttpURLConnection
                    val catalogue = url.host == "api.github.com" &&
                        (url.path.startsWith("/repos/panel-assistant/android/releases") ||
                            url.path.startsWith("/repos/home-assistant/android/releases"))
                    if (!catalogue) {
                        if (url.path.endsWith(".apk", ignoreCase = true)) apks += url.toString()
                        throw IOException("non-catalogue request refused by scheduled-install fixture")
                    }
                    catalogues += url.toString()
                    return object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getResponseCode() = 200
                        override fun getContentLengthLong() = 2L
                        override fun getInputStream() = ByteArrayInputStream("[]".toByteArray())
                    }
                }
            }
        }
    }

    private companion object {
        val LEGACY_VALUES = mapOf(
            "self_update" to true,
            "update_channel" to "stable",
            "companion_auto_update" to true,
            "companion_update_channel" to "stable",
            "webview_auto_update" to true,
        )
    }
}
