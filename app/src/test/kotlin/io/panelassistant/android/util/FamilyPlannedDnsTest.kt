package io.panelassistant.android.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ordering and filtering contract of the HA WebSocket address-family policy. The shapes mirror a
 * real dual-stack failure: a Home Assistant host publishing an AAAA that a panel's network segment
 * black-holes while its A record works, where a single-address dial can never reach the sibling
 * family.
 */
class FamilyPlannedDnsTest {

    private val v6a: InetAddress = InetAddress.getByName("2001:db8::1")
    private val v6b: InetAddress = InetAddress.getByName("2001:db8::2")
    private val v6c: InetAddress = InetAddress.getByName("2001:db8::3")
    private val v4a: InetAddress = InetAddress.getByName("192.0.2.1")
    private val v4b: InetAddress = InetAddress.getByName("192.0.2.2")

    private fun dns(
        preferIpv4: Boolean = false,
        ipv4Only: Boolean = false,
        answers: List<InetAddress>,
    ) = FamilyPlannedDns(preferIpv4, ipv4Only) { answers }

    @Test fun automaticKeepsTheResolversLeadingFamily() {
        assertEquals(
            listOf(v6a, v4a, v6b),
            dns(answers = listOf(v6a, v6b, v4a)).lookup("ha.example"),
        )
        assertEquals(
            listOf(v4a, v6a, v4b),
            dns(answers = listOf(v4a, v4b, v6a)).lookup("ha.example"),
        )
    }

    @Test fun aDeadLeadingFamilyCostsExactlyOneRouteBeforeTheSibling() {
        // A host publishing several AAAAs ahead of one A. Concatenated ordering would spend one
        // bounded connect per dead AAAA before the A; interleaving pins the A to slot 1.
        val ordered = dns(answers = listOf(v6a, v6b, v4a)).lookup("ha.example")
        assertTrue("first IPv4 must be the second route, was $ordered", ordered[1] is Inet4Address)
    }

    @Test fun preferIpv4LeadsWithARecordsAndKeepsEveryAddress() {
        val ordered = dns(preferIpv4 = true, answers = listOf(v6a, v6b, v4a, v4b)).lookup("ha.example")
        assertEquals(listOf(v4a, v6a, v4b, v6b), ordered)
    }

    @Test fun forceIpv4NeverEmitsAnIpv6Address() {
        assertEquals(
            listOf(v4a, v4b),
            dns(ipv4Only = true, answers = listOf(v6a, v4a, v4b)).lookup("ha.example"),
        )
    }

    @Test fun forceIpv4OnAnIpv6OnlyHostFailsWithAClearVerdict() {
        // Mirrors the MQTT planner: a force-IPv4 plan never silently falls back to IPv6, it names
        // the conflict so the configuration surface can show it.
        try {
            dns(ipv4Only = true, answers = listOf(v6a, v6b)).lookup("ha.example")
            fail("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
            assertTrue(
                "message names the policy conflict: ${expected.message}",
                expected.message.orEmpty().contains("Force IPv4"),
            )
        }
    }

    @Test fun everyAddressSurvivesWhenTheTrailingFamilyIsLarger() {
        // One A record leading several AAAAs (a common dual-stack publication under Prefer IPv4):
        // the interleave loop must run until BOTH families are exhausted. A loop keyed on the
        // leading family alone silently drops the extra trailing addresses - and a dropped address
        // is a fallback route that no longer exists.
        val ordered = dns(preferIpv4 = true, answers = listOf(v6a, v6b, v6c, v4a)).lookup("ha.example")
        assertEquals(listOf(v4a, v6a, v6b, v6c), ordered)
    }

    @Test fun singleFamilyAnswersPassThroughUnchanged() {
        assertEquals(listOf(v6a, v6b), dns(answers = listOf(v6a, v6b)).lookup("ha.example"))
        assertEquals(listOf(v4a, v4b), dns(answers = listOf(v4a, v4b)).lookup("ha.example"))
    }

    @Test fun relativeOrderWithinAFamilyIsPreserved() {
        val ordered = dns(answers = listOf(v6b, v6a, v4b, v4a)).lookup("ha.example")
        assertEquals(listOf(v6b, v4b, v6a, v4a), ordered)
    }
}
