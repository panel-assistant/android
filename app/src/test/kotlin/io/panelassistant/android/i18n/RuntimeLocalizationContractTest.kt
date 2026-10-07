package io.panelassistant.android.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLocalizationContractTest {
    private val assets = File("src/main/assets")
    // Source-text reason: loads the shipped i18n catalogues as input data.
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
        // Source-text reason: runtime.* catalogue keys used anywhere in the app are a translation catalogue contract.
        val consumers = listOf(File("src/main/kotlin"), assets)
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "js") }.toList() }
            .flatMapTo(sortedSetOf()) { literalRuntimeKeys(it.readText()) }

        assertTrue("the production runtime call-site inventory must not shrink", consumers.size >= 41)
        assertTrue("all newly authored runtime records must have literal consumers", consumers.containsAll(newRuntimeKeys))
        assertTrue(
            "runtime call sites are missing English catalogue records: ${consumers - source.strings.keys}",
            source.strings.keys.containsAll(consumers),
        )
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
}
