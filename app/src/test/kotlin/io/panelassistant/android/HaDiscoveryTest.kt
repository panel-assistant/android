package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract that keeps discovery honest: every "cannot work here" verdict must be earned, and anything
 * unjudgeable must degrade to "found nothing" rather than assert a cause. The regression these guard is a
 * cross-subnet panel being left at a blank Home Assistant URL with no explanation.
 */
class HaDiscoveryTest {
    @Test
    fun anIpFormAdvertisedUrlIsRewrittenToTheRecordsOwnHostname() {
        // HA commonly advertises internal_url in IP form; suggestions built from it pinned panels to one
        // IPv4 address (hardware review). The record's server field names the same machine.
        assertEquals(
            "http://homeassistant.local:8123",
            HaDiscovery.preferServerHostname("http://192.0.2.8:8123", "homeassistant.local."),
        )
        // Hostname-form URLs are untouched; a missing/IP-form server changes nothing.
        assertEquals(
            "https://hass.example.net:443",
            HaDiscovery.preferServerHostname("https://hass.example.net:443", "homeassistant.local."),
        )
        assertEquals(
            "http://192.0.2.8:8123",
            HaDiscovery.preferServerHostname("http://192.0.2.8:8123", "198.51.100.5"),
        )
        assertEquals(
            "http://192.0.2.8:8123",
            HaDiscovery.preferServerHostname("http://192.0.2.8:8123", null),
        )
        assertEquals(null, HaDiscovery.preferServerHostname(null, "homeassistant.local."))
        // IPv6-literal advertised hosts count as IP-form too.
        assertEquals(
            "http://homeassistant.local:8123",
            HaDiscovery.preferServerHostname("http://[fd00::8]:8123", "homeassistant.local"),
        )
    }

    private val lan24 = listOf(LocalPrefix("192.168.1.42", 24))

    @Test fun brokerOnTheSameLanIsNotOffLink() {
        assertEquals(false, HaDiscovery.brokerOffLink(listOf("192.168.1.10"), lan24))
    }

    @Test fun brokerBeyondTheLocalPrefixIsOffLink() {
        assertEquals(true, HaDiscovery.brokerOffLink(listOf("10.99.0.121"), lan24))
    }

    @Test fun prefixLengthIsHonouredRatherThanAssumedToBeSlash24() {
        // 10.2.4.209 is outside 10.2.5.0/24 but inside 10.2.0.0/16. Assuming /24 would call a reachable
        // broker off-link and wrongly tell the user discovery can never work.
        assertEquals(true, HaDiscovery.brokerOffLink(listOf("10.2.4.209"), listOf(LocalPrefix("10.2.5.1", 24))))
        assertEquals(false, HaDiscovery.brokerOffLink(listOf("10.2.4.209"), listOf(LocalPrefix("10.2.5.1", 16))))
    }

    @Test fun aNonByteAlignedPrefixIsComparedBitwise() {
        // 10.0.3.5 is inside 10.0.0.0/22 (covers 10.0.0-3.x) but outside /23 (covers 10.0.0-1.x).
        assertEquals(false, HaDiscovery.brokerOffLink(listOf("10.0.3.5"), listOf(LocalPrefix("10.0.0.1", 22))))
        assertEquals(true, HaDiscovery.brokerOffLink(listOf("10.0.3.5"), listOf(LocalPrefix("10.0.0.1", 23))))
    }

    @Test fun reachableOnAnySingleInterfaceWins() {
        val twoNics = listOf(LocalPrefix("192.168.1.42", 24), LocalPrefix("10.2.5.1", 16))
        assertEquals(false, HaDiscovery.brokerOffLink(listOf("10.2.4.209"), twoNics))
    }

    @Test fun anyOnLinkBrokerAddressWinsOverAnOffLinkOne() {
        // A broker host that resolves to both a global v6 and a LAN v4 is reachable without a router.
        val prefixes = listOf(LocalPrefix("192.168.1.42", 24), LocalPrefix("fd00::42", 64))
        assertEquals(false, HaDiscovery.brokerOffLink(listOf("2001:db8::1", "192.168.1.10"), prefixes))
    }

    @Test fun ipv6IsJudgedAgainstIpv6Prefixes() {
        val v6 = listOf(LocalPrefix("fd00::42", 64))
        assertEquals(false, HaDiscovery.brokerOffLink(listOf("fd00::10"), v6))
        assertEquals(true, HaDiscovery.brokerOffLink(listOf("fd01::10"), v6))
    }

    @Test fun unjudgeableInputsReturnNullRatherThanGuess() {
        // No broker address to test.
        assertNull(HaDiscovery.brokerOffLink(emptyList(), lan24))
        assertNull(HaDiscovery.brokerOffLink(listOf("not-an-ip"), lan24))
        // The platform reported no prefix length: assuming one is exactly the mistake to avoid.
        assertNull(HaDiscovery.brokerOffLink(listOf("192.168.1.10"), listOf(LocalPrefix("192.168.1.42", 0))))
        assertNull(HaDiscovery.brokerOffLink(listOf("192.168.1.10"), emptyList()))
        // An IPv6-only broker with only IPv4 prefixes to compare against is not judgeable.
        assertNull(HaDiscovery.brokerOffLink(listOf("2001:db8::1"), lan24))
    }

    @Test fun hostnamesNeverTriggerAResolveFromThePureDecision() {
        assertNull(HaDiscovery.parseIpLiteral("homeassistant.local"))
        assertNull(HaDiscovery.parseIpLiteral(""))
        assertNull(HaDiscovery.parseIpLiteral("1234"))
        assertNotNull(HaDiscovery.parseIpLiteral("192.168.1.1"))
        // Brackets and a zone index are how addresses arrive from broker URLs and NetworkInterface.
        assertNotNull(HaDiscovery.parseIpLiteral("[fd00::1]"))
        assertNotNull(HaDiscovery.parseIpLiteral("fe80::1%wlan0"))
    }

    @Test fun aSuccessfulDiscoverySettlesItRegardlessOfEveryOtherSignal() {
        val r = HaDiscovery.classify(
            mdnsRunning = false,
            multicastLockHeld = false,
            brokerConfigured = true,
            brokerOffLink = true,
            servicesSeen = 0,
            discoveredUrl = "http://homeassistant.local:8123",
            attemptedAtMs = 7L,
            failed = true,
        )
        assertEquals(DiscoveryOutcome.FOUND, r.outcome)
        assertEquals("http://homeassistant.local:8123", r.value)
        assertEquals(DiscoveryReason.NONE, r.reason)
        assertFalse(r.hopeless)
    }

    @Test fun notListeningIsReportedBeforeClaimingHomeAssistantIsElsewhere() {
        // Claiming "HA is on another segment" would assert a search we never ran.
        val r = HaDiscovery.classify(
            mdnsRunning = false, multicastLockHeld = true, brokerConfigured = true,
            brokerOffLink = true, servicesSeen = SERVICES_SEEN_UNKNOWN, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryReason.MDNS_NOT_RUNNING, r.reason)
        assertTrue(r.hopeless)
    }

    @Test fun aMissingMulticastLockIsReportedAsAPanelFact() {
        val r = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = false, brokerConfigured = false,
            brokerOffLink = null, servicesSeen = SERVICES_SEEN_UNKNOWN, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryReason.NO_MULTICAST_LOCK, r.reason)
        assertEquals(DiscoveryOutcome.UNAVAILABLE, r.outcome)
    }

    @Test fun anOffLinkBrokerMakesDiscoveryHopelessNotMerelyEmpty() {
        val r = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true, brokerConfigured = true,
            brokerOffLink = true, servicesSeen = 3, discoveredUrl = "", attemptedAtMs = 5L,
        )
        assertEquals(DiscoveryOutcome.UNAVAILABLE, r.outcome)
        assertEquals(DiscoveryReason.BROKER_NOT_ON_LINK, r.reason)
        assertEquals(5L, r.attemptedAtMs)
    }

    @Test fun anUnjudgeableOrOnLinkBrokerDegradesToNoneFound() {
        // This is the safety property: "don't know" must never become "cannot work".
        val unjudgeable = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true, brokerConfigured = true,
            brokerOffLink = null, servicesSeen = 2, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryOutcome.NONE_FOUND, unjudgeable.outcome)
        assertFalse(unjudgeable.hopeless)
        val onLink = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true, brokerConfigured = true,
            brokerOffLink = false, servicesSeen = 2, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryOutcome.NONE_FOUND, onLink.outcome)
    }

    @Test fun anUnconfiguredBrokerNeverTriggersTheOffLinkInference() {
        // With no broker configured there is no address to reason from, so the verdict must not be used.
        val r = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true, brokerConfigured = false,
            brokerOffLink = true, servicesSeen = 4, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryOutcome.NONE_FOUND, r.outcome)
    }

    @Test fun silenceIsTheWeakestInferenceAndIsSkippedWhenUncounted() {
        val silent = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true, brokerConfigured = false,
            brokerOffLink = null, servicesSeen = 0, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryReason.NO_RESPONSES_AT_ALL, silent.reason)
        val uncounted = HaDiscovery.classify(
            mdnsRunning = true, multicastLockHeld = true, brokerConfigured = false,
            brokerOffLink = null, servicesSeen = SERVICES_SEEN_UNKNOWN, discoveredUrl = "", attemptedAtMs = 1L,
        )
        assertEquals(DiscoveryOutcome.NONE_FOUND, uncounted.outcome)
    }

    @Test fun explanationsExistForEveryUnavailableReasonAndNoneOtherwise() {
        assertNull(HaDiscovery.unavailableExplanation(DiscoveryResult()))
        assertNull(HaDiscovery.unavailableExplanation(DiscoveryResult(DiscoveryOutcome.NONE_FOUND)))
        assertNull(
            HaDiscovery.unavailableExplanation(DiscoveryResult(DiscoveryOutcome.FOUND, "http://ha:8123")),
        )
        DiscoveryReason.entries.filter { it != DiscoveryReason.NONE }.forEach { reason ->
            val text = HaDiscovery.unavailableExplanation(
                DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = reason),
            )
            assertNotNull("no explanation for $reason", text)
            // Each explanation is embedded mid-sentence by the caller, so it must not be pre-punctuated.
            // (It may still begin with a capital when it opens on a proper noun such as Home Assistant.)
            assertTrue("explanation for $reason should not be blank", text!!.isNotBlank())
            assertFalse("explanation for $reason must compose mid-sentence", text.trimEnd().endsWith("."))
        }
    }

    @Test fun confidenceIsCarriedInTheGrammarOfEachExplanation() {
        val inferred = HaDiscovery.unavailableExplanation(
            DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = DiscoveryReason.BROKER_NOT_ON_LINK),
        )!!
        val guess = HaDiscovery.unavailableExplanation(
            DiscoveryResult(DiscoveryOutcome.UNAVAILABLE, reason = DiscoveryReason.NO_RESPONSES_AT_ALL),
        )!!
        // The sound inference states what is so; the guess must hedge. Overclaiming here is what makes a
        // diagnosis actively misleading rather than merely unhelpful.
        assertTrue(inferred.contains("is on a different network segment"))
        assertFalse(inferred.contains("may"))
        assertTrue(guess.contains("may"))
    }
}
