package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsFleetI18nContractTest {
    private val assets = File("src/main/assets")
    private val serverSource = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val sourceRecords = JSONObject(File(assets, "i18n/en.json").readText()).getJSONObject("strings")
    private val sourceCatalogue = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    private val expectedKeys = mapOf(
        "logs" to setOf(
            "logs.action.clear",
            "logs.action.follow",
            "logs.action.pause",
            "logs.action.resume",
            "logs.filter.placeholder",
            "logs.level.debug",
            "logs.level.error",
            "logs.level.info",
            "logs.level.minimum",
            "logs.level.verbose",
            "logs.level.warning",
            "logs.note.privacy",
            "logs.note.raw_stream",
            "logs.note.sources",
            "logs.source.app",
            "logs.source.system",
            "logs.source.system_root_check",
            "logs.source.webview",
            "logs.source.webview_hint",
            "logs.state.app_live",
            "logs.state.app_paused",
            "logs.state.connecting",
            "logs.state.hidden",
            "logs.state.reconnecting",
            "logs.state.system_live",
            "logs.state.system_paused",
            "logs.state.webview_live",
            "logs.state.webview_paused",
            "logs.title",
        ),
        "fleet" to setOf(
            "fleet.note.direct",
            "fleet.note.discovery_prefix",
            "fleet.note.discovery_suffix",
            "fleet.note.roster",
            "fleet.state.coming_soon",
            "fleet.title",
        ),
    )

    @Test fun `Logs and Fleet finite browser copy is catalogue backed without former hard coded English`() {
        val formerVisibleEnglish = mapOf(
            "logs" to listOf(
                "Live logs",
                "connecting…",
                "Root availability is checked when the stream opens",
                "Minimum level",
                "filter text…",
                "Tokens/passwords are redacted before display",
            ),
            "fleet" to listOf(
                "Fleet overview",
                "coming soon",
                "A multi-panel roster",
                "For now, open another panel directly",
            ),
        )

        expectedKeys.forEach { (surface, expected) ->
            val body = functionBody("${surface}Body")
            val browserScript = if (surface == "logs") File(assets, "logs.js").readText() else ""
            val consumed = literalKeys(body, surface, "strings\\.get") +
                literalKeys(browserScript, surface, "i18nText")
            assertEquals("$surface must consume its complete bounded catalogue slice", expected, consumed)
            formerVisibleEnglish.getValue(surface).forEach { literal ->
                assertFalse("$surface still hard-codes visible English: $literal", body.contains(literal))
            }
            expected.forEach { key ->
                val entry = sourceCatalogue.strings[key]
                assertTrue("English catalogue is missing $key", entry != null)
                assertEquals("$key is assigned to the wrong surface", surface, sourceRecords.getJSONObject(key).getString("surface"))
            }
        }
    }

    @Test fun `Logs and Fleet catalogue slices are current and promoted in every release locale`() {
        val allExpected = expectedKeys.values.flatten().toSet()

        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), sourceCatalogue)
            allExpected.forEach { key ->
                val english = checkNotNull(sourceCatalogue.strings[key]) { "English catalogue is missing $key" }
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale must promote $key beyond draft before release",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT),
                )
            }
        }
    }

    @Test fun `Logs server controls retain their intended catalogue key mappings`() {
        val body = functionBody("logsBody")
        val expectedBindings = listOf(
            "id=\"lg-src-app\"" to "logs.source.app",
            "id=\"lg-src-system\"" to "logs.source.system",
            "id=\"lg-src-webview\"" to "logs.source.webview",
            "value=\"V\"" to "logs.level.verbose",
            "value=\"D\"" to "logs.level.debug",
            "value=\"I\"" to "logs.level.info",
            "value=\"W\"" to "logs.level.warning",
            "value=\"E\"" to "logs.level.error",
            "id=\"lg-follow\"" to "logs.action.follow",
            "id=\"lg-pause\"" to "logs.action.pause",
            "onclick=\"lgClear()\"" to "logs.action.clear",
        )
        expectedBindings.forEachIndexed { index, (control, key) ->
            val start = body.indexOf(control)
            val end = expectedBindings.getOrNull(index + 1)?.first?.let { body.indexOf(it, start + control.length) }
                ?.takeIf { it >= 0 } ?: body.length
            assertTrue("logsBody is missing control $control", start >= 0)
            assertTrue(
                "$control must consume $key before the next control",
                body.substring(start, end).contains("strings.get(\"$key\")"),
            )
        }
    }

    @Test fun `Logs and Fleet routes report body languages and project their active prefixes through page`() {
        listOf("logs", "fleet").forEach { route ->
            val routeSource = routeBody(route)
            assertTrue("/$route must vary on the requested language", routeSource.contains("HttpHeaders.AcceptLanguage"))
            assertTrue("/$route must emit Content-Language", routeSource.contains("HttpHeaders.ContentLanguage"))
            assertTrue(
                "/$route Content-Language must account for shell, shared hardened descriptions and the $route body catalogue",
                Regex("strings\\.languages\\(setOf\\(\\s*\"shell\\.\"\\s*,\\s*\"configure\\.hardened\\.\"\\s*,\\s*\"$route\\.\"\\s*\\)\\)")
                    .containsMatchIn(routeSource),
            )
            assertFalse(
                "/$route must not report unconditional English once its complete target catalogue is active",
                routeSource.contains("+ AppLocale.ENGLISH"),
            )
            assertTrue(
                "/$route must pass its request-localized Strings through page()",
                Regex("page\\(\"$route\"[\\s\\S]*?,\\s*strings,\\s*call\\.embedMode\\(\\)\\)").containsMatchIn(routeSource),
            )
        }

        val page = functionBody("page")
        assertTrue(
            "page() must project the active body prefix for browser-side translations",
            page.contains("translationPrefixes = setOf(\"shell.\", \"${'$'}active.\", \"runtime.\")"),
        )
    }

    @Test fun `Logs and Fleet language accounting exposes shared per-key English fallback`() {
        val targetJson = JSONObject(File(assets, "i18n/de.json").readText())
        targetJson.getJSONObject("strings")
            .getJSONObject("configure.hardened.action_approval")
            .put("state", "machine-draft")
        val target = TargetCatalogue.parse(targetJson.toString(), sourceCatalogue)
        val strings = Strings(source = sourceCatalogue, target = target)

        listOf("logs", "fleet").forEach { surface ->
            assertEquals(
                "shared hidden fallback must be represented for /$surface",
                listOf("de", "en"),
                strings.languages(setOf("shell.", "configure.hardened.", "$surface.")),
            )
        }
    }

    @Test fun `Logs and Fleet leave opaque evidence and machine addresses outside translated prose`() {
        val logs = functionBody("logsBody")
        val fleet = functionBody("fleetBody")

        assertTrue("raw log endpoint must remain a copyable machine address", logs.contains("/api/v1/logs/stream"))
        assertTrue("raw log endpoint must retain the configured machine port", logs.contains("${'$'}{esc(config.httpPort.toString())}"))
        assertTrue("Fleet mDNS service token must remain verbatim", fleet.contains("${'$'}{esc(Config.MDNS_SERVICE_TYPE)}"))
        assertTrue("Fleet direct URL must remain a copyable machine address", fleet.contains("http://&lt;its-ip&gt;:${'$'}{esc(config.httpPort.toString())}/"))
        val discoveryPrefix = fleet.indexOf("strings.get(\"fleet.note.discovery_prefix\")")
        val serviceToken = fleet.indexOf("<code>${'$'}{esc(Config.MDNS_SERVICE_TYPE)}</code>")
        val discoverySuffix = fleet.indexOf("strings.get(\"fleet.note.discovery_suffix\")")
        assertTrue(
            "Fleet mDNS token must remain a separately escaped code boundary between translatable grammar",
            discoveryPrefix >= 0 && discoveryPrefix < serviceToken && serviceToken < discoverySuffix,
        )

        listOf(logs, fleet).forEach { body ->
            assertFalse("machine addresses must not be inserted into translated text", Regex("strings\\.get\\([^)]*http").containsMatchIn(body))
        }
    }

    private fun literalKeys(source: String, prefix: String, function: String): Set<String> =
        Regex("$function\\(\\s*[\\\"'](($prefix)\\.[a-z0-9._-]+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun functionBody(name: String): String {
        val start = serverSource.indexOf("fun $name(").also { require(it >= 0) { "missing function $name" } }
        val next = serverSource.indexOf("\n    private fun ", start + 1).takeIf { it >= 0 } ?: serverSource.length
        return serverSource.substring(start, next)
    }

    private fun routeBody(path: String): String {
        val marker = "get(\"/$path\")"
        val start = serverSource.indexOf(marker).also { require(it >= 0) { "missing /$path route" } }
        val next = serverSource.indexOf("\n                get(\"/", start + marker.length)
            .takeIf { it >= 0 } ?: serverSource.length
        return serverSource.substring(start, next)
    }
}
