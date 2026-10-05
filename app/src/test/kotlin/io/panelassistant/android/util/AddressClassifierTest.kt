package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the shared address classifier in Net.kt ([isLoopbackPeer] / [isLocalSource] /
 * [isRoutable]) that replaced the three duplicated PaneldServer classifiers. Each of the five LAN-local
 * predicate branches is exercised, plus the IPv4-mapped-IPv6 unmapping, the zone/`/` stripping, the
 * unparseable failure semantics, and the equivalence relationships the three public entry points share.
 */
class AddressClassifierTest {

    // --- isLocalSource: each of the five LAN-local predicate branches returns true ---

    @Test fun loopbackIsLocal() {
        assertTrue(isLocalSource("127.0.0.1"))
        assertTrue(isLocalSource("::1"))
    }

    @Test fun linkLocalIsLocal() {
        assertTrue(isLocalSource("169.254.1.5"))   // IPv4 link-local
        assertTrue(isLocalSource("fe80::1"))       // IPv6 link-local
    }

    @Test fun siteLocalRfc1918IsLocal() {
        assertTrue(isLocalSource("192.168.1.10"))
        assertTrue(isLocalSource("10.0.0.5"))
        assertTrue(isLocalSource("172.16.4.209"))
    }

    @Test fun anyLocalWildcardIsLocal() {
        assertTrue(isLocalSource("0.0.0.0"))
        assertTrue(isLocalSource("::"))
    }

    @Test fun ipv6UlaIsLocal() {
        assertTrue(isLocalSource("fc00::1"))       // fc00::/8
        assertTrue(isLocalSource("fd12:3456::1"))  // fd00::/8, both inside fc00::/7
    }

    // --- routable public addresses are NOT local ---

    @Test fun globalAddressesAreNotLocal() {
        assertFalse(isLocalSource("8.8.8.8"))
        assertFalse(isLocalSource("1.1.1.1"))
        assertFalse(isLocalSource("2001:4860:4860::8888"))
    }

    // --- IPv4-mapped IPv6 unmapping: a public IPv4 wrapped as ::ffff:a.b.c.d must not read as local ---

    @Test fun ipv4MappedPublicIsNotLocal() {
        assertFalse(isLocalSource("::ffff:8.8.8.8"))
        assertTrue(isRoutable("::ffff:8.8.8.8"))
    }

    @Test fun ipv4MappedPrivateIsLocal() {
        assertTrue(isLocalSource("::ffff:192.168.1.10"))
        assertFalse(isRoutable("::ffff:192.168.1.10"))
    }

    // --- zone id and leading '/' stripping (as remoteAddress / InetAddress.toString carry) ---

    @Test fun stripsLeadingSlash() {
        assertTrue(isLocalSource("/192.168.1.10"))
        assertTrue(isLoopbackPeer("/127.0.0.1"))
    }

    @Test fun stripsZoneId() {
        assertTrue(isLocalSource("fe80::1%eth0"))
        assertTrue(isLoopbackPeer("::1%lo"))
    }

    // --- isLoopbackPeer: loopback-only slice ---

    @Test fun loopbackPeerAcceptsOnlyLoopback() {
        assertTrue(isLoopbackPeer("127.0.0.1"))
        assertTrue(isLoopbackPeer("::1"))
        assertFalse(isLoopbackPeer("192.168.1.10"))   // LAN-local but not loopback
        assertFalse(isLoopbackPeer("fc00::1"))         // ULA but not loopback
        assertFalse(isLoopbackPeer("8.8.8.8"))         // routable
    }

    // --- failure semantics: unparseable input returns false from ALL THREE ---

    @Test fun unparseableReturnsFalseEverywhere() {
        // Values that make getByName throw (UnknownHostException / IllegalArgumentException) → all false.
        // NB: getByName("") resolves to loopback in the JVM, so the empty string is deliberately NOT here —
        // it is a genuine loopback input under both the original and consolidated classifiers.
        for (bad in listOf("—", "not-an-address!", "999.999.999.999")) {
            assertFalse("isLoopbackPeer($bad)", isLoopbackPeer(bad))
            assertFalse("isLocalSource($bad)", isLocalSource(bad))
            assertFalse("isRoutable($bad)", isRoutable(bad))
        }
    }

    // --- equivalence: isRoutable == !isLocalSource for parseable input; both false for unparseable ---

    @Test fun routableIsNegationOfLocalForParseable() {
        for (addr in listOf(
            "127.0.0.1", "::1", "192.168.1.10", "10.0.0.5", "169.254.1.5",
            "fe80::1", "fc00::1", "fd00::1", "0.0.0.0", "::",
            "8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "::ffff:8.8.8.8", "::ffff:192.168.1.10",
        )) {
            assertEquals("isRoutable == !isLocalSource for $addr", !isLocalSource(addr), isRoutable(addr))
        }
    }

    @Test fun routableAndLocalBothFalseForUnparseable() {
        // The one place isRoutable is NOT the strict negation of isLocalSource: an unparseable value
        // returns false from BOTH (rather than isRoutable returning true), preserving the original
        // getOrDefault(false) failure semantics of each classifier.
        assertFalse(isLocalSource("—"))
        assertFalse(isRoutable("—"))
    }

    @Test fun loopbackPeerIsTheLoopbackSliceOfLocalSource() {
        // Every loopback peer is also a local source; not every local source is a loopback peer.
        for (addr in listOf("127.0.0.1", "::1")) {
            assertTrue(isLoopbackPeer(addr))
            assertTrue(isLocalSource(addr))
        }
        for (addr in listOf("192.168.1.10", "fc00::1", "fe80::1")) {
            assertFalse(isLoopbackPeer(addr))
            assertTrue(isLocalSource(addr))
        }
    }
}
