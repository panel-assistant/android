package io.panelassistant.android

import java.net.InetAddress
import javax.jmdns.impl.HostInfo
import javax.jmdns.impl.constants.DNSRecordClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address records a panel advertises for each shape of default network. The advertiser binds one
 * JmDNS responder to [MdnsLanAddresses.primary] and, on a dual-stack network, one to
 * [MdnsLanAddresses.secondary]; JmDNS's [HostInfo] then answers only the bound address's family. This
 * runs that real record path for the addresses the selection hands the advertiser.
 */
class MdnsAdvertisedAddressRecordsTest {
    @Test fun ipv4OnlyPanelAdvertisesExactlyItsIpv4Record() {
        val selected = defaultNetworkMdnsAddresses(addresses("fe80::1", "127.0.0.1", "192.0.2.10"))

        assertEquals(MdnsLanAddresses("192.0.2.10"), selected)
        assertEquals(setOf("A 192.0.2.10"), advertisedAddressRecords(selected))
    }

    @Test fun ipv6OnlyPanelAdvertisesItsIpv6Record() {
        val selected = defaultNetworkMdnsAddresses(addresses("fe80::1", "::1", "fd00:5041::10"))

        assertEquals(setOf("AAAA fd00:5041:0:0:0:0:0:10"), advertisedAddressRecords(selected))
    }

    @Test fun dualStackPanelAdvertisesBothFamiliesWithIpv4AsItsPrimary() {
        val selected = defaultNetworkMdnsAddresses(addresses("fd00:5041::10", "192.0.2.10", "fe80::1"))

        assertEquals("192.0.2.10", selected?.primary)
        assertEquals(
            setOf("A 192.0.2.10", "AAAA fd00:5041:0:0:0:0:0:10"),
            advertisedAddressRecords(selected),
        )
    }

    @Test fun noUsableAddressAdvertisesNothing() {
        assertNull(defaultNetworkMdnsAddresses(addresses("::1", "fe80::1", "ff02::fb")))
    }

    @Test fun stableUniqueLocalAddressIsPreferredToTemporaryOrGlobalOnes() {
        val temporary = InetAddress.getByName("fd00:5041::bad")
        val all = addresses("2001:db8::20", "fd00:5041::bad") + temporary + addresses("fd00:5041::10")

        assertEquals("fd00:5041:0:0:0:0:0:10", defaultNetworkIpv6(all, setOf(temporary)))
        assertEquals(
            "a stable global address beats a temporary unique-local one",
            "2001:db8:0:0:0:0:0:20",
            defaultNetworkIpv6(addresses("fd00:5041::bad", "2001:db8::20"), setOf(temporary)),
        )
        assertEquals(
            "a temporary address is still better than advertising nothing",
            "fd00:5041:0:0:0:0:0:bad",
            defaultNetworkIpv6(listOf(temporary), setOf(temporary)),
        )
    }

    @Test fun onlyThePrimaryAddressDefinesTheTopologyEpoch() {
        val topology = MdnsTopology()
        val first = topology.request("192.0.2.10", "fd00:5041::10")
        assertTrue(first.changed)
        assertEquals("fd00:5041::10", first.secondaryIp)

        val secondaryMoved = topology.request("192.0.2.10", "fd00:5041::11")
        assertFalse("an IPv6 change alone must not reset the IPv4 liveness supervisor", secondaryMoved.changed)
        assertEquals(first.epoch, secondaryMoved.epoch)
        assertEquals("fd00:5041::11", topology.snapshot().secondaryIp)

        assertTrue(topology.request("192.0.2.11", "fd00:5041::11").changed)
        assertNull("stop forgets the IPv6 responder too", topology.stop().secondaryIp)
        assertNull(topology.snapshot().secondaryIp)
        assertNull("no secondary without a primary", topology.request(null, "fd00:5041::11").secondaryIp)
    }

    @Test fun staleAddressWarningComparesEachFamilyWithItsOwnAuthority() {
        assertEquals(
            "an IPv4 binding still compares with the interface's live IPv4",
            "192.0.2.11",
            mdnsHealthLanIp("192.0.2.10", "192.0.2.10") { "192.0.2.11" },
        )
        assertEquals(
            "an IPv6 binding is not called stale because some interface has IPv4",
            "fd00:5041::10",
            mdnsHealthLanIp("fd00:5041::10", "fd00:5041::10") { "192.0.2.11" },
        )
        assertEquals(
            "an IPv6-only panel that is not advertising still reports its address",
            "fd00:5041::10",
            mdnsHealthLanIp(null, "fd00:5041::10") { null },
        )
    }

    @Test fun ipv6ProbeQueriesTheIpv6Group() {
        assertEquals("224.0.0.251", mdnsGroupFor(InetAddress.getByName("192.0.2.10")))
        assertEquals("ff02::fb", mdnsGroupFor(InetAddress.getByName("fd00:5041::10")))
    }

    @Test fun ipv6ProbeCountsAReplyFromAnyAddressOfItsOwnInterface() {
        val bound = "fd00:5041:0:0:0:0:0:10"
        val temporarySibling = "fd00:5041:0:0:0:0:0:bad"
        val own = setOf(bound, temporarySibling)
        assertEquals(temporarySibling, mdnsProbeExpectedSource(temporarySibling, own, bound))
        assertEquals(temporarySibling, mdnsProbeExpectedSource("$temporarySibling%wlan0", own, bound).substringBefore('%'))
        assertEquals("a reply from another host is never this panel's", bound, mdnsProbeExpectedSource("fd00:5041:0:0:0:0:0:99", own, bound))
        assertEquals("IPv4 keeps its exact-source rule", "192.0.2.10", mdnsProbeExpectedSource("192.0.2.11", emptySet(), "192.0.2.10"))
    }

    private fun advertisedAddressRecords(selected: MdnsLanAddresses?): Set<String> =
        listOfNotNull(selected?.primary, selected?.secondary).flatMap { bound ->
            HostInfo.newHostInfo(InetAddress.getByName(bound), null, "panel")
                .answers(DNSRecordClass.CLASS_IN, true, 120)
                .map { record ->
                    "${record.recordType.name.removePrefix("TYPE_")} " +
                        record.getServiceInfo(false).inetAddresses.single().hostAddress
                }
        }.toSet()

    private fun addresses(vararg values: String): List<InetAddress> = values.map(InetAddress::getByName)
}
