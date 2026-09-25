package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.DiscoveryOutcome
import io.github.maxlyth.hapaneld.DiscoveryReason
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.HaDiscovery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After saving MQTT credentials the user must always be told what to do next.
 *
 * The regression this guards is specific and was observed on hardware: the post-save guidance used to
 * return null whenever `ha_url` was still blank, and it tries to fill `ha_url` by mDNS. A panel on a
 * different network segment from Home Assistant can never satisfy that — mDNS is link-local — so the
 * URL stayed blank, the message was suppressed, and the user was left staring at a settings page with no
 * indication that anything had happened or what came next. Discovery failing is a normal, supported
 * network topology, not an error state, and it must produce guidance rather than silence.
 */
class MqttOnboardingGuidanceContractTest {
    @Test fun theExplanationReadsAsOneSentenceForEveryUnavailableReason() {
        // The message embeds the explanation as "...because <reason>." so every reason must compose.
        DiscoveryReason.entries.filter { it != DiscoveryReason.NONE }.forEach { reason ->
            val why = HaDiscovery.unavailableExplanation(
                DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = reason),
            )!!
            val sentence = "It could not be found automatically because $why."
            assertFalse("double punctuation for $reason", sentence.contains(".."))
            assertTrue(sentence.endsWith("."))
        }
    }

    @Test fun theCrossSubnetCaseNamesTheNetworkSegmentPlainly() {
        // The wording a cross-subnet user actually sees. It must explain the topology in plain language,
        // without jargon such as mDNS, multicast or subnet, and without implying a fault.
        val why = HaDiscovery.unavailableExplanation(
            DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = DiscoveryReason.BROKER_NOT_ON_LINK),
        )!!
        assertTrue(why.contains("different network segment"))
        listOf("mDNS", "multicast", "subnet", "zeroconf").forEach {
            assertFalse("$it is jargon for this audience", why.contains(it, ignoreCase = true))
        }
    }
}
