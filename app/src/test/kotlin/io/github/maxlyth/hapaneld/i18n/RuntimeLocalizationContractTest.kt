package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLocalizationContractTest {
    private val assets = File("src/main/assets")
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
    private val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
    private val newRuntimeKeys = setOf(
        "runtime.power_safety.ack.not_hidden",
        "runtime.power_safety.action.acknowledgeable",
        "runtime.power_safety.action.acknowledged",
        "runtime.power_safety.action.manual",
        "runtime.power_safety.action.repair_degraded",
        "runtime.power_safety.action.repair_direct",
        "runtime.power_safety.action.repair_limited",
        "runtime.power_safety.action.review",
        "runtime.power_safety.button.repair",
        "runtime.power_safety.button.repair_title",
        "runtime.power_safety.level.at_risk",
        "runtime.power_safety.level.caution",
        "runtime.power_safety.level.unknown",
        "runtime.power_safety.repair.approval",
        "runtime.power_safety.repair.failed_no_reboot",
        "runtime.power_safety.repair.partial",
        "runtime.power_safety.repair.repaired",
        "runtime.power_safety.summary.at_risk",
        "runtime.power_safety.summary.caution",
        "runtime.power_safety.summary.unknown",
        "runtime.renderer_recovery.builtin",
        "runtime.renderer_recovery.external",
        "runtime.zigbee.warning.contained",
        "runtime.zigbee.warning.containment_failed",
        "runtime.zigbee.warning.degraded_high_cpu",
        "runtime.zigbee.warning.degraded_unjoined",
        "runtime.zigbee.warning.legacy_watchdog",
        "runtime.zigbee.warning.resolve",
        "runtime.zigbee.warning.runaway",
    )

    @Test fun `every literal runtime call site has a current promoted catalogue record`() {
        val consumers = listOf(File("src/main/kotlin"), assets)
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "js") }.toList() }
            .flatMapTo(sortedSetOf()) { literalRuntimeKeys(it.readText()) }

        assertEquals("the release-blocker runtime addition changed", 29, newRuntimeKeys.size)
        assertEquals("the complete production runtime call-site inventory changed", 41, consumers.size)
        assertTrue("all newly authored runtime records must have literal consumers", consumers.containsAll(newRuntimeKeys))
        assertTrue(
            "runtime call sites are missing English catalogue records: ${consumers - source.strings.keys}",
            source.strings.keys.containsAll(consumers),
        )

        AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), source)
            consumers.forEach { key ->
                val english = checkNotNull(source.strings[key]) { "English is missing $key" }
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale must promote runtime call-site key $key",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED ||
                        (locale in AppLocale.EARLY_ACCESS_LOCALES &&
                            translated.state == TranslationState.MACHINE_DRAFT) ||
                        EarlyAccessReviewHold.holds(locale, key, translated),
                )
            }
        }
    }

    @Test fun `every shared page projects runtime strings with provenance`() {
        val kotlin = server.readText()
        assertTrue(functionBody(kotlin, "browserI18nPayload").contains("\"runtime.\" in prefixes"))
        assertTrue(functionBody(kotlin, "page").contains("setOf(\"shell.\", \"\$active.\", \"runtime.\")"))
        assertTrue(functionBody(kotlin, "infoHtml").contains("setOf(\"shell.\", \"dashboard.\", \"runtime.\")"))
        assertTrue(routeBody(kotlin, "setup").contains("setOf(\"shell.\", \"setup.\", \"runtime.\")"))
    }

    @Test fun `Italian and French power safety use the established risk terminology`() {
        val italian = TargetCatalogue.parse(File(assets, "i18n/it.json").readText(), source)
        val italianKeys = setOf(
            "runtime.power_safety.level.at_risk",
            "runtime.power_safety.level.caution",
            "runtime.power_safety.level.unknown",
            "runtime.power_safety.summary.caution",
            "runtime.power_safety.summary.unknown",
            "runtime.power_safety.action.review",
            "runtime.power_safety.action.repair_direct",
            "runtime.power_safety.action.repair_degraded",
            "runtime.power_safety.action.repair_limited",
            "runtime.power_safety.button.repair",
            "runtime.power_safety.repair.approval",
            "runtime.power_safety.repair.repaired",
            "runtime.power_safety.repair.partial",
            "runtime.power_safety.repair.failed_no_reboot",
        )
        italianKeys.forEach { key ->
            val text = checkNotNull(italian.strings[key]).text
            assertTrue("$key must use the established Italian power-safety term", "dell’alimentazione" in text)
            assertTrue("$key must not revert to energy terminology", !Regex("energi", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }

        val french = TargetCatalogue.parse(File(assets, "i18n/fr.json").readText(), source)
        assertEquals(
            "Gestion de l’alimentation du panneau : à risque",
            checkNotNull(french.strings["runtime.power_safety.level.at_risk"]).text,
        )
    }

    private fun literalRuntimeKeys(text: String): Set<String> =
        Regex("[\\\"'](runtime(?:\\.[a-z0-9_-]+)+)[\\\"']")
            .findAll(text)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun functionBody(text: String, name: String): String {
        val start = text.indexOf("fun $name(").also { require(it >= 0) { "missing function $name" } }
        val next = text.indexOf("\n    private fun ", start + 1).takeIf { it >= 0 } ?: text.length
        return text.substring(start, next)
    }

    private fun routeBody(text: String, path: String): String {
        val marker = "get(\"/$path\")"
        val start = text.indexOf(marker).also { require(it >= 0) { "missing /$path route" } }
        val next = text.indexOf("\n                get(\"/", start + marker.length)
            .takeIf { it >= 0 } ?: text.length
        return text.substring(start, next)
    }
}
