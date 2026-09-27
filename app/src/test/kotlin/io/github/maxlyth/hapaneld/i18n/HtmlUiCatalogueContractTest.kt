package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.http.PaneldServer
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HtmlUiCatalogueContractTest {
    private val assets = File("src/main/assets")
    // Source-text reason: whole-file scans for literal catalogue keys (translation catalogue contract), not code structure.
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
    // Source-text reason: loads the shipped i18n catalogues and page scripts' literal key/fallback pairs as catalogue input.
    private val catalogue = JSONObject(File(assets, "i18n/en.json").readText()).getJSONObject("strings")
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    @Test fun `shell Dashboard Configure Profiles and Entities literal keys are present and owned by their source surface`() {
        val buildwatch = File(assets, "buildwatch.js").readText()
        val switcher = File(assets, "switcher.js").readText()
        val usages = linkedMapOf(
            "shell" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(buildwatch, "i18nText") +
                    literalKeys(switcher, "i18nText")
                ).filterTo(sortedSetOf()) { it.startsWith("shell.") },
            "dashboard" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "info.js").readText(), "i18nText") +
                    literalKeys(buildwatch, "i18nText")
                ).filterTo(sortedSetOf()) { it.startsWith("dashboard.") },
            "configure" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "configure.js").readText(), "i18nText") +
                    literalKeys(File(assets, "proximity-learning.js").readText(), "t").filterNot { it.endsWith(".") }
                ).filterTo(sortedSetOf()) { it.startsWith("configure.") },
            "profiles" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "profiles.js").readText(), "t")
                ).filterTo(sortedSetOf()) { it.startsWith("profiles.") },
            "entities" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "entities.js").readText(), "t")
                ).filterTo(sortedSetOf()) { it.startsWith("entities.") },
        )

        usages.forEach { (surface, allKeys) ->
            val keys = allKeys.filterTo(sortedSetOf()) { it.startsWith("$surface.") }
            assertFalse("$surface must have literal catalogue consumers", keys.isEmpty())
            keys.forEach { key ->
                assertTrue("$key is used but absent from the English catalogue", catalogue.has(key))
                assertEquals("$key is assigned to the wrong catalogue surface", surface, catalogue.getJSONObject(key).getString("surface"))
            }
        }
    }

    @Test fun `release target HTML UI slices including Install and API are complete current and promoted`() {
        val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
        val promotedSurfaces = setOf("shell", "dashboard", "configure", "profiles", "entities", "install", "api")
        val expected = source.strings.filterKeys { key ->
            catalogue.getJSONObject(key).getString("surface") in promotedSurfaces
        }

        assertEquals("the complete source catalogue is a reviewed release contract", 2465, source.strings.size)
        assertEquals("the declared promoted HTML UI preview scope must not shrink silently", 2033, expected.size)
        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), source)
            assertEquals(
                "$locale must contain the complete release catalogue",
                2465,
                target.strings.size,
            )
            assertEquals(
                "$locale target keys must exactly match the reviewed English source catalogue",
                source.strings.keys,
                target.strings.keys,
            )
            expected.forEach { (key, sourceString) ->
                val translated = checkNotNull(target.strings[key]) { "$locale HTML UI slice is missing $key" }
                assertEquals("$locale has stale source text for $key", sourceString.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale must promote HTML UI key $key beyond draft before release",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                    translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (translated.state == TranslationState.ENGLISH_FALLBACK &&
                            locale to key in APPROVED_PROFILES_ENGLISH_FALLBACKS) ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT) ||
                        EarlyAccessReviewHold.holds(locale, key, translated),
                )
            }
        }
    }

    @Test fun `Zigbee join confirmation keeps its consequential paragraph structure in every release locale`() {
        val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
        val key = "configure.zigbee.join_confirm"
        val english = checkNotNull(source.strings[key]) { "English catalogue is missing $key" }.text
        val englishParagraphs = english.split("\n\n")

        assertEquals("the consequential English confirmation must remain a three-paragraph contract", 3, englishParagraphs.size)
        assertTrue("the English confirmation must not contain isolated line breaks", englishParagraphs.none { '\n' in it })
        releaseTargetLocales.forEach { locale ->
            val translated = targetText(locale, key)
            val translatedParagraphs = translated.split("\n\n")

            assertEquals(
                "$locale $key must retain the source's three-paragraph confirmation structure",
                englishParagraphs.size,
                translatedParagraphs.size,
            )
            assertTrue("$locale $key must not contain an empty paragraph", translatedParagraphs.none { it.isBlank() })
            assertTrue("$locale $key must not replace paragraph breaks with isolated line breaks", translatedParagraphs.none { '\n' in it })
        }
    }

    @Test fun `translated guidance names internal controls exactly as their localized labels`() {
        val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
        val internalReferences = mapOf(
            "shell.nav.configure" to setOf(
                "dashboard.banner.auth_rejected.action",
                "dashboard.banner.schema_rollback.configure_action",
                "dashboard.banner.setup_needs.suffix",
                "dashboard.camera.configure_link",
                "dashboard.camera.delivery.short_help",
                "dashboard.capability.note.shizuku_disabled",
                "dashboard.link.edit_configure",
            ),
            "shell.nav.install" to setOf(
                "dashboard.banner.companion_url.install_action",
                "dashboard.banner.manage_install",
                "dashboard.link.open_install",
            ),
            "dashboard.controls.admin_launcher" to setOf(
                "dashboard.controls.no_separate_launcher",
                "dashboard.controls.root_required_note",
            ),
            "dashboard.controls.launcher" to setOf(
                "dashboard.controls.root_required_note",
            ),
            "dashboard.controls.reboot" to setOf(
                "dashboard.controls.root_required_note",
            ),
            "configure.group.dashboard" to setOf(
                "configure.setup.renderer.body",
                "setup.renderer.failure.explanation",
            ),
            "shell.nav.dashboard" to setOf(
                "setup.proof.dashboard_help",
            ),
        )

        releaseTargetLocales.forEach { locale ->
            internalReferences.forEach { (labelKey, referenceKeys) ->
                val englishLabel = checkNotNull(source.strings[labelKey]) { "English catalogue is missing $labelKey" }.text
                val localizedLabel = targetText(locale, labelKey)
                // A label held at English cannot be named exactly by guidance that is still translated.
                if (EarlyAccessReviewHold.holdsText(locale, labelKey, localizedLabel)) return@forEach

                referenceKeys.forEach { referenceKey ->
                    val guidance = targetText(locale, referenceKey)
                    if (EarlyAccessReviewHold.holdsText(locale, referenceKey, guidance)) return@forEach
                    val guidanceWithoutLongerLabel = if (labelKey == "dashboard.controls.launcher") {
                        guidance.replace(targetText(locale, "dashboard.controls.admin_launcher"), "")
                    } else {
                        guidance
                    }
                    assertTrue(
                        "$locale $referenceKey must name $labelKey exactly as the visible localized control '$localizedLabel'",
                        localizedLabel in guidanceWithoutLongerLabel,
                    )
                    if (localizedLabel != englishLabel) {
                        assertFalse(
                            "$locale $referenceKey must not retain the internal English control name '$englishLabel'",
                            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(englishLabel)}(?![\\p{L}\\p{N}])").containsMatchIn(guidanceWithoutLongerLabel),
                        )
                    }
                }
            }
        }

        val externalChromeLabels = setOf("dashboard.inspect.instructions", "dashboard.inspect.running")
        releaseTargetLocales.forEach { locale ->
            externalChromeLabels.forEach { key ->
                assertTrue(
                    "$locale $key must preserve Chrome DevTools' external Configure… label",
                    "Configure…" in targetText(locale, key),
                )
            }
        }
    }

    @Test fun `every human tab keeps an explicit Simplified Chinese shell and navigation`() {
        val chinese = CatalogueLoader { name -> File(assets, name).readText() }.strings("zh-Hans")
        assertEquals("zh-Hans", chinese.requestedLocale)
        assertFalse("the Dashboard shell sentinel must be translated", chinese.get("shell.nav.dashboard") == "Dashboard")
        assertFalse("the Configure shell sentinel must be translated", chinese.get("shell.nav.configure") == "Configure")

        val serverInstance = unsafe().allocateInstance(PaneldServer::class.java) as PaneldServer
        val localizedHref = PaneldServer::class.java.getDeclaredMethod(
            "localizedHref",
            String::class.java,
            Strings::class.java,
        ).apply { isAccessible = true }
        assertEquals(
            "/configure?lang=zh-Hans#cfg-camera",
            localizedHref.invoke(serverInstance, "/configure#cfg-camera", chinese),
        )
        assertEquals(
            "/install?repair=1&lang=zh-Hans#camera",
            localizedHref.invoke(serverInstance, "/install?repair=1#camera", chinese),
        )
    }

    @Test fun `shared runtime literal English fallbacks match their authoritative records`() {
        val scripts = listOf("buildwatch.js", "switcher.js").associateWith { File(assets, it).readText() }
        val bindings = scripts.flatMap { (name, source) ->
            literalFallbackBindings(source).map { (key, fallback) -> Triple(name, key, fallback) }
        }

        assertTrue("shared runtime scripts must expose auditable literal fallback bindings", bindings.isNotEmpty())
        bindings.forEach { (name, key, fallback) ->
            assertTrue("$name consumes $key but English does not define it", catalogue.has(key))
            assertEquals(
                "$name fallback for $key drifted from i18n/en.json",
                catalogue.getJSONObject(key).getString("text"),
                fallback,
            )
        }

        // Lifecycle entries are deliberately held in a closed data map and selected by wire state;
        // bind that map's literal key/text pairs as strongly as direct i18nText calls.
        val lifecycleBindings = Regex(
            "key:\\s*[\\\"']((?:shell|dashboard)\\.[a-z0-9._-]+)[\\\"']\\s*,\\s*" +
                "text:\\s*[\\\"']([^\\\"']*)[\\\"']",
        ).findAll(checkNotNull(scripts["buildwatch.js"]))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
        assertEquals("the lifecycle map must remain a finite four-state projection", 4, lifecycleBindings.size)
        lifecycleBindings.forEach { (key, fallback) ->
            assertEquals(
                "buildwatch.js lifecycle fallback for $key drifted from i18n/en.json",
                catalogue.getJSONObject(key).getString("text"),
                fallback,
            )
        }
    }

    @Test fun `shared runtime chrome owns exactly its finite 33-key catalogue addition`() {
        val addedKeys = setOf(
            "shell.settings_changed.externally",
            "shell.runtime.ha_lifecycle.offline",
            "shell.runtime.ha_lifecycle.starting",
            "shell.runtime.ha_lifecycle.back_online",
            "shell.runtime.ha_lifecycle.shutting_down",
            "shell.runtime.ha_network.banner_warning",
            "shell.runtime.ha_network.banner_severe",
            "shell.runtime.ha_network.banner_latency_warning",
            "shell.runtime.ha_network.banner_latency_severe",
            "shell.runtime.duration_seconds",
            "shell.runtime.duration_minutes",
            "shell.runtime.ha_network_evidence_no_probes",
            "shell.runtime.ha_network_evidence_no_answer",
            "shell.runtime.ha_network_evidence_no_reply_no_misses",
            "shell.runtime.ha_network_evidence_no_reply_missed",
            "shell.runtime.ha_network_evidence_p95_no_misses",
            "shell.runtime.ha_network_evidence_p95_missed",
            "shell.panel_switcher.title",
            "dashboard.runtime.ha_network_healthy_slow",
            "dashboard.runtime.ha_network_healthy_very_slow",
            "dashboard.runtime.ha_network_losing_probes",
            "dashboard.runtime.ha_network_losing_probes_slow",
            "dashboard.runtime.ha_network_losing_probes_very_slow",
            "dashboard.runtime.ha_network_failing",
            "dashboard.runtime.ha_network_failing_slow",
            "dashboard.runtime.ha_network_failing_very_slow",
            "dashboard.runtime.ha_network_settling",
            "dashboard.runtime.ha_network_latency_warning",
            "dashboard.runtime.ha_network_latency_warning_response_slow",
            "dashboard.runtime.ha_network_latency_warning_response_very_slow",
            "dashboard.runtime.ha_network_latency_severe",
            "dashboard.runtime.ha_network_latency_severe_response_slow",
            "dashboard.runtime.ha_network_latency_severe_response_very_slow",
        )
        assertEquals("the reviewed shared-runtime addition changed", 33, addedKeys.size)
        assertEquals("shared copy needed outside Dashboard must project through the shell", 18, addedKeys.count { it.startsWith("shell.") })
        assertEquals("only diagnostics-row templates belong to Dashboard", 15, addedKeys.count { it.startsWith("dashboard.") })
        addedKeys.forEach { key ->
            assertTrue("English is missing shared-runtime key $key", catalogue.has(key))
            assertEquals(
                "$key is assigned to the wrong projection surface",
                key.substringBefore('.'),
                catalogue.getJSONObject(key).getString("surface"),
            )
        }

        val latencyFallbacks = linkedMapOf(
            "shell.runtime.ha_network.banner_latency_warning" to
                "The network path to Home Assistant is slow. Every action waits on this path. It is measured at the network level, so this is not the panel or Home Assistant being slow — check the Wi-Fi or the link between them.",
            "shell.runtime.ha_network.banner_latency_severe" to
                "The network path to Home Assistant is very slow. Every action waits on this path. It is measured at the network level, so this is not the panel or Home Assistant being slow — check the Wi-Fi or the link between them.",
            "dashboard.runtime.ha_network_latency_warning" to "slow",
            "dashboard.runtime.ha_network_latency_warning_response_slow" to "slow; Home Assistant answering slowly",
            "dashboard.runtime.ha_network_latency_warning_response_very_slow" to "slow; Home Assistant answering very slowly",
            "dashboard.runtime.ha_network_latency_severe" to "very slow",
            "dashboard.runtime.ha_network_latency_severe_response_slow" to "very slow; Home Assistant answering slowly",
            "dashboard.runtime.ha_network_latency_severe_response_very_slow" to "very slow; Home Assistant answering very slowly",
        )
        val buildwatch = File(assets, "buildwatch.js").readText()
        latencyFallbacks.forEach { (key, fallback) ->
            assertTrue("buildwatch must bind $key through a finite literal pair", buildwatch.contains("[\"$key\", \"$fallback\"]"))
            assertEquals("$key fallback drifted from the English catalogue", fallback, catalogue.getJSONObject(key).getString("text"))
        }
        assertFalse("latency copy must not borrow WebSocket evidence", latencyFallbacks.values.any { "{evidence}" in it })
    }

    private fun literalKeys(source: String, function: String): Set<String> =
        Regex("$function\\(\\s*[\\\"']((?:shell|dashboard|configure|profiles|entities)\\.[a-z0-9._-]+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun literalFallbackBindings(source: String): List<Pair<String, String>> =
        Regex(
            "i18nText\\(\\s*([\\\"'])((?:shell|dashboard)\\.[a-z0-9._-]+)\\1\\s*,\\s*" +
                "([\\\"'])((?:\\\\.|(?!\\3).)*)\\3",
        ).findAll(source).map { match ->
            match.groupValues[2] to decodeJsLiteral(match.groupValues[4])
        }.toList()

    private fun decodeJsLiteral(value: String): String = value
        .replace("\\\\'", "'")
        .replace("\\\\\"", "\"")
        .replace("\\\\n", "\n")
        .replace("\\\\\\\\", "\\")

    private fun targetText(locale: String, key: String): String {
        val strings = JSONObject(File(assets, "i18n/$locale.json").readText()).getJSONObject("strings")
        require(strings.has(key)) { "$locale is missing $key" }
        return strings.getJSONObject(key).getString("text")
    }

    private fun unsafe(): Unsafe {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as Unsafe
    }
}
