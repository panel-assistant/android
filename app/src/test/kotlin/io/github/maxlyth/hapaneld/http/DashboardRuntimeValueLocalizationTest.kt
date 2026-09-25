package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.RendererAdmissionPresentation
import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.i18n.Strings
import java.io.File
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class DashboardRuntimeValueLocalizationTest {
    // Source-text reason: loads the shipped i18n catalogues as input data.
    private val loader = CatalogueLoader { path -> File("src/main/assets", path).readText() }
    private val zh = loader.strings("zh-Hans")
    private val en = loader.strings("en")
    private val server = (unsafe().allocateInstance(PaneldServer::class.java) as PaneldServer).also {
        // Unsafe skips the large production constructor, including these two dispatch-key initializers.
        setField(it, "HA_RENDERER_FACT", "HA renderer")
        setField(it, "CAMERA_FACT", "Camera")
    }
    private val localize: Method = PaneldServer::class.java.getDeclaredMethod(
        "localizedRuntimeValue", String::class.java, String::class.java, Strings::class.java,
    ).apply { isAccessible = true }

    @Test fun everyOrdinaryBuiltInRendererStateUsesTheSelectedCatalogue() {
        data class Case(val source: String, val key: String, val age: String? = null, val theme: Boolean = false)
        val cases = listOf(
            Case("dashboard rendered", "dashboard.runtime.renderer.rendered"),
            Case("dashboard rendered", "dashboard.runtime.renderer.rendered", "2m"),
            Case(
                "dashboard rendered; admitted on a previously verified Home Assistant version",
                "dashboard.runtime.renderer.rendered_cached",
                "47s",
            ),
            Case(
                "admitted on a previously verified Home Assistant version; the dashboard has not connected",
                "dashboard.runtime.renderer.admitted_cached",
                "5m12s",
            ),
            Case("admitted; the dashboard has not connected yet", "dashboard.runtime.renderer.admitted_waiting", "9s"),
            Case("checking Home Assistant compatibility", "dashboard.runtime.renderer.checking", "3s"),
            Case("no admission observed for the current renderer", "dashboard.runtime.renderer.unobserved"),
            Case("dashboard rendered", "dashboard.runtime.renderer.rendered", theme = true),
            Case("dashboard rendered", "dashboard.runtime.renderer.rendered", "1h2m", theme = true),
        )

        cases.forEach { case ->
            val sourceSummary = case.source + if (case.theme) RendererAdmissionPresentation.OVERRIDDEN_SUMMARY_SUFFIX else ""
            val sourceStatus = "built-in · $sourceSummary"
            val raw = case.age?.let { "$sourceStatus · admitted $it ago" } ?: sourceStatus
            val localizedSummary = zh.get(case.key) +
                if (case.theme) zh.get("dashboard.runtime.renderer.theme_override_suffix") else ""
            val status = format(zh, "dashboard.runtime.renderer.builtin", "summary" to localizedSummary)
            val expected = case.age?.let {
                format(zh, "dashboard.runtime.renderer.admitted_age", "status" to status, "age" to it)
            } ?: status
            val actual = translated("HA renderer", raw, zh)

            assertEquals(raw, expected, actual)
            assertNotEquals(raw, actual)
            case.age?.let { assertEvidence(actual, it) }
            assertNoEnglish(actual, "built-in ·", case.source)
            if (case.theme) assertFalse(actual.contains(RendererAdmissionPresentation.OVERRIDDEN_SUMMARY_SUFFIX))
        }
    }

    @Test fun everyMqttStateLocalizesWithoutChangingProtocolOrAgeEvidence() {
        val states = listOf(
            "connected", "announcing", "auth-retrying", "auth-failed", "unreachable", "connecting",
            "discovering", "disconnected",
        )
        states.forEachIndexed { index, state ->
            val transport = if (index % 2 == 0) "TLS/IPv6" else "TCP/IPv4"
            val (familyRaw, familyExpected, familyEvidence) = when (index % 3) {
                0 -> Triple(
                    "Automatic (next IPv6)",
                    format(zh, "dashboard.runtime.mqtt.family_automatic", "family" to "IPv6"),
                    "IPv6",
                )
                1 -> Triple("Prefer IPv4", zh.get("dashboard.runtime.mqtt.family_prefer_ipv4"), "IPv4")
                else -> Triple("Force IPv4", zh.get("dashboard.runtime.mqtt.family_force_ipv4"), "IPv4")
            }
            val raw = "$state · $transport · last-ok 12s ago · last-auth never · " +
                "auth-ok · family $familyRaw"
            val expected = format(
                zh,
                "dashboard.runtime.mqtt.status",
                "state" to zh.get("dashboard.runtime.mqtt.state.${state.replace('-', '_')}"),
                "transport" to transport,
                "lastOk" to format(zh, "dashboard.runtime.mqtt.age_seconds_ago", "seconds" to "12"),
                "lastAuth" to zh.get("dashboard.runtime.mqtt.age_never"),
                "auth" to "auth-ok",
                "family" to familyExpected,
            )
            val actual = translated("MQTT state", raw, zh)

            assertEquals(state, expected, actual)
            assertNotEquals(raw, actual)
            assertEvidence(actual, transport, "12", "auth-ok", familyEvidence)
            assertFalse("raw MQTT family scaffold leaked in $actual", actual.contains(familyRaw))
            assertNoEnglish(actual, state, "last-ok", "last-auth", "never", "family")
        }
    }

    @Test fun mqttForceIpv4RetryWithNoNextAttemptAndConfigurationErrorAreLocalized() {
        val raw = "auth-failed · TLS/IPv4 · last-ok never · last-auth 7s ago · " +
            "auth-failed · rejects 4 · attempt 5 · next none · family Force IPv4"
        val state = zh.get("dashboard.runtime.mqtt.state.auth_failed")
        val auth = format(
            zh,
            "dashboard.runtime.mqtt.auth_retry",
            "state" to state,
            "rejects" to "4",
            "attempt" to "5",
            "next" to zh.get("dashboard.value.none"),
        )
        val expected = format(
            zh,
            "dashboard.runtime.mqtt.status",
            "state" to state,
            "transport" to "TLS/IPv4",
            "lastOk" to zh.get("dashboard.runtime.mqtt.age_never"),
            "lastAuth" to format(zh, "dashboard.runtime.mqtt.age_seconds_ago", "seconds" to "7"),
            "auth" to auth,
            "family" to zh.get("dashboard.runtime.mqtt.family_force_ipv4"),
        )
        val actual = translated("MQTT state", raw, zh)
        assertEquals(expected, actual)
        assertEvidence(actual, "TLS/IPv4", "4", "5", "7", "IPv4")
        assertNoEnglish(actual, "auth-failed", "last-ok", "last-auth", "never", "rejects", "attempt", "next", "none", "family", "Force")

        val timedRaw = "auth-retrying · TCP/IPv4 · last-ok 13s ago · last-auth never · " +
            "auth-retrying · rejects 2 · attempt 3 · next 9s · family Prefer IPv4"
        val retrying = zh.get("dashboard.runtime.mqtt.state.auth_retrying")
        val timedAuth = format(
            zh,
            "dashboard.runtime.mqtt.auth_retry",
            "state" to retrying,
            "rejects" to "2",
            "attempt" to "3",
            "next" to format(zh, "dashboard.runtime.mqtt.seconds", "seconds" to "9"),
        )
        assertEquals(
            format(
                zh,
                "dashboard.runtime.mqtt.status",
                "state" to retrying,
                "transport" to "TCP/IPv4",
                "lastOk" to format(zh, "dashboard.runtime.mqtt.age_seconds_ago", "seconds" to "13"),
                "lastAuth" to zh.get("dashboard.runtime.mqtt.age_never"),
                "auth" to timedAuth,
                "family" to zh.get("dashboard.runtime.mqtt.family_prefer_ipv4"),
            ),
            translated("MQTT state", timedRaw, zh),
        )

        val errorRaw = "config-error · invalid or unsupported broker URL"
        val error = translated("MQTT state", errorRaw, zh)
        assertEquals(zh.get("dashboard.runtime.mqtt.config_error"), error)
        assertNotEquals(errorRaw, error)
        assertNoEnglish(error, "config-error", "invalid", "unsupported broker")
    }

    @Test fun databaseOptionalShapesLocalizeLabelsWithoutChangingMeasurements() {
        listOf(
            "1.5 MB used" to format(zh, "dashboard.runtime.database.used", "bytes" to "1.5 MB"),
            "1.5 MB used · 2.0 MB on disk" to (
                format(zh, "dashboard.runtime.database.used", "bytes" to "1.5 MB") + " · " +
                    format(zh, "dashboard.runtime.database.on_disk", "bytes" to "2.0 MB")
                ),
            "1.5 MB used · schema 11" to (
                format(zh, "dashboard.runtime.database.used", "bytes" to "1.5 MB") + " · " +
                    format(zh, "dashboard.runtime.database.schema", "version" to "11")
                ),
            "1.5 MB used · 2.0 MB on disk · schema 11" to (
                format(zh, "dashboard.runtime.database.used", "bytes" to "1.5 MB") + " · " +
                    format(zh, "dashboard.runtime.database.on_disk", "bytes" to "2.0 MB") + " · " +
                    format(zh, "dashboard.runtime.database.schema", "version" to "11")
                ),
        ).forEach { (raw, expected) ->
            val actual = translated("App database", raw, zh)
            assertEquals(raw, expected, actual)
            Regex("""\d+(?:\.\d+)? (?:[KMGTPE]?B)|(?<=schema )\d+""").findAll(raw).forEach {
                assertEvidence(actual, it.value)
            }
            assertNoEnglish(actual, " used", "on disk", "schema")
        }
    }

    @Test fun everyCameraRuntimeTemplateHasEnglishParityAndAChineseRendering() {
        data class Case(val raw: String, val expected: String, val evidence: List<String> = emptyList())
        val url = "rtsp://192.0.2.1:8554/live"
        val noStreamEn = en.get("dashboard.runtime.camera.stream_not_listening")
        val noStreamZh = zh.get("dashboard.runtime.camera.stream_not_listening")
        val portEn = format(en, "dashboard.runtime.camera.stream_port", "port" to "8554")
        val portZh = format(zh, "dashboard.runtime.camera.stream_port", "port" to "8554")
        val urlEn = format(en, "dashboard.runtime.camera.stream_url", "url" to url)
        val urlZh = format(zh, "dashboard.runtime.camera.stream_url", "url" to url)
        val cases = listOf(
            Case("camera off", zh.get("dashboard.runtime.camera.off")),
            Case(
                "camera on, but Android has not granted the permission",
                zh.get("dashboard.runtime.camera.permission_needed"),
                listOf("Android"),
            ),
            Case("camera stopping", zh.get("dashboard.runtime.camera.stopping")),
            Case("camera opening; $noStreamEn", format(zh, "dashboard.runtime.camera.opening", "stream" to noStreamZh)),
            Case("camera opening; $portEn", format(zh, "dashboard.runtime.camera.opening", "stream" to portZh), listOf("8554")),
            Case("camera opening; $urlEn", format(zh, "dashboard.runtime.camera.opening", "stream" to urlZh), listOf(url)),
            Case("camera closed; nobody is watching; $urlEn", format(zh, "dashboard.runtime.camera.idle", "stream" to urlZh), listOf(url)),
            Case(
                "camera gave up after 3 failures (encode); $noStreamEn",
                format(zh, "dashboard.runtime.camera.degraded", "count" to "3", "fault" to "encode", "stream" to noStreamZh),
                listOf("3", "encode"),
            ),
            Case(
                "camera open for 1 client; $portEn",
                format(zh, "dashboard.runtime.camera.live_one", "count" to "1", "stream" to portZh),
                listOf("1", "8554"),
            ),
            Case(
                "camera open for 2 clients; $portEn",
                format(zh, "dashboard.runtime.camera.live_many", "count" to "2", "stream" to portZh),
                listOf("2", "8554"),
            ),
            Case(
                "camera open for 1 client (1 streaming); $urlEn",
                format(zh, "dashboard.runtime.camera.live_one_streaming", "count" to "1", "streaming" to "1", "stream" to urlZh),
                listOf("1", url),
            ),
            Case(
                "camera open for 2 clients (1 streaming); $noStreamEn",
                format(zh, "dashboard.runtime.camera.live_many_streaming", "count" to "2", "streaming" to "1", "stream" to noStreamZh),
                listOf("2", "1"),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                "the English catalogue must reproduce the exact Camera runtime source template",
                case.raw,
                translated("Camera", case.raw, en),
            )
            val actual = translated("Camera", case.raw, zh)
            assertEquals(case.raw, case.expected, actual)
            assertNotEquals(case.raw, actual)
            assertEvidence(actual, *case.evidence.toTypedArray())
        }
    }

    @Test fun unknownEvidenceStaysVerbatimWhileRecognizedHaEvidenceLocalizesOnlyItsPrefix() {
        val unknown = "vendor probe E42 · /dev/example <opaque>&"
        assertEquals(unknown, translated("unrecognized fact", unknown, zh))

        val evidence = "RTT p95 842 ms via fd00::7"
        val raw = "healthy; $evidence"
        val actual = translated("HA network path", raw, zh)
        assertEquals(format(zh, "dashboard.runtime.ha_network_healthy", "evidence" to evidence), actual)
        assertNotEquals(raw, actual)
        assertEvidence(actual, evidence)
        assertEquals("recognized evidence must occur exactly once", 1, actual.windowed(evidence.length).count { it == evidence })
        assertNoEnglish(actual, "healthy;")
    }

    private fun translated(key: String, value: String, strings: Strings): String =
        localize.invoke(server, key, value, strings) as String

    private fun format(strings: Strings, key: String, vararg values: Pair<String, String>): String =
        values.fold(strings.get(key)) { text, (name, value) -> text.replace("{$name}", value) }

    private fun assertEvidence(actual: String, vararg evidence: String) {
        evidence.forEach { assertTrue("missing technical evidence $it in $actual", actual.contains(it)) }
    }

    private fun assertNoEnglish(actual: String, vararg fragments: String) {
        fragments.forEach {
            assertFalse("raw English scaffold '$it' leaked in $actual", actual.contains(it, ignoreCase = true))
        }
    }

    private fun unsafe(): Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
        isAccessible = true
        get(null) as Unsafe
    }

    private fun setField(instance: PaneldServer, name: String, value: String) {
        PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
}
