package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure mDNS peer mapping/dedupe that backs the header panel switcher — the logic that's testable without
 * a JmDNS `ServiceInfo` or a device. [toPeer] turns record fields into a [Peer]; [dedupePeers] collapses
 * duplicate records and orders the roster.
 */
class MdnsPeerTest {

    @Test fun obsoleteRefreshGenerationCannotUseReplacementResolver() {
        val generation = AtomicLong(4)
        val old = generation.get()
        assertTrue(mdnsRunCurrent(generation, old, browsing = true, ownsResolver = true))
        generation.incrementAndGet()
        assertFalse(mdnsRunCurrent(generation, old, browsing = true, ownsResolver = true))
        assertFalse(mdnsRunCurrent(generation, generation.get(), browsing = false, ownsResolver = true))
        assertFalse(mdnsRunCurrent(generation, generation.get(), browsing = true, ownsResolver = false))
    }

    private fun peer(id: String, name: String? = null, ip: String? = "10.0.0.9", ver: String? = "1.2.3", self: Boolean = false) =
        toPeer(id, name, ver, ip, 8888, selfId = if (self) id else "someone_else", selfIp = null)

    // --- toPeer ---
    @Test fun nameFallsBackToInstanceWhenTxtMissingOrBlank() {
        assertEquals("gamma_ha_paneld", toPeer("gamma_ha_paneld", null, "9", "10.0.0.1", 8888, "x", null).name)
        assertEquals("gamma_ha_paneld", toPeer("gamma_ha_paneld", "  ", "9", "10.0.0.1", 8888, "x", null).name)
        assertEquals("Gamma Panel", toPeer("gamma_ha_paneld", "Gamma Panel", "9", "10.0.0.1", 8888, "x", null).name)
    }

    @Test fun versionFallsBackToEmpty() {
        assertEquals("", toPeer("p", "P", null, "10.0.0.1", 8888, "x", null).version)
        assertEquals("0.8.6", toPeer("p", "P", "0.8.6", "10.0.0.1", 8888, "x", null).version)
    }

    @Test fun selfMatchesById() =
        assertTrue(toPeer("alpha_ha_paneld", "Alpha", "9", "10.0.0.5", 8888, selfId = "alpha_ha_paneld", selfIp = "10.0.0.99").self)

    @Test fun selfMatchesByIp() =
        assertTrue(toPeer("alpha_ha_paneld", "Alpha", "9", "10.0.0.5", 8888, selfId = "other", selfIp = "10.0.0.5").self)

    @Test fun notSelfWhenNeitherMatches() =
        assertFalse(toPeer("beta_ha_paneld", "Beta", "9", "10.0.0.5", 8888, selfId = "alpha_ha_paneld", selfIp = "10.0.0.99").self)

    @Test fun unresolvedIpIsNull() =
        assertNull(toPeer("p", "P", "9", null, 8888, "x", null).ip)

    @Test fun selfIpNullNeverFalseMatchesUnresolvedPeer() =
        assertFalse("null selfIp must not match a null peer ip", toPeer("beta", "Beta", "9", null, 8888, "alpha", null).self)

    // --- dedupePeers ---
    @Test fun dedupesByPanelIdPreferringResolvedIp() {
        val out = dedupePeers(listOf(peer("beta_ha_paneld", ip = null), peer("beta_ha_paneld", ip = "10.0.0.7")))
        assertEquals(1, out.size)
        assertEquals("10.0.0.7", out[0].ip)
    }

    @Test fun dropsBlankPanelIds() =
        assertTrue(dedupePeers(listOf(peer("", ip = "10.0.0.1"))).isEmpty())

    @Test fun ordersPurelyByNameSoEveryPanelMatches() {
        // Self is flagged, NOT pulled to the top — the roster must be identical on every panel so the
        // switcher menu looks the same everywhere.
        val out = dedupePeers(
            listOf(
                peer("z", name = "Zzz Panel"),
                peer("a", name = "Aaa Panel"),
                peer("me", name = "My Panel", self = true),
            ),
        )
        assertEquals(listOf("Aaa Panel", "My Panel", "Zzz Panel"), out.map { it.name })
    }

    // --- peersJson (the /api/v1/peers serializer the switcher fetches) ---
    @Test fun peersJsonEmptyListIsEmptyArray() = assertEquals("[]", peersJson(emptyList()))

    @Test fun peersJsonResolvedPeerCarriesIpAndDerivedUrl() {
        val json = peersJson(listOf(peer("gamma_ha_paneld", name = "Gamma", ip = "10.0.0.7", ver = "0.8.6")))
        assertEquals(
            """[{"panel_id":"gamma_ha_paneld","name":"Gamma","ip":"10.0.0.7","port":8888,""" +
                """"url":"http://10.0.0.7:8888/","version":"0.8.6","self":false}]""",
            json,
        )
    }

    @Test fun peersJsonUnresolvedIpEmitsLiteralNullForIpAndUrl() {
        // A peer whose IPv4 didn't resolve is un-navigable — ip/url MUST be JSON null (not "null"),
        // so switcher.js can filter it out rather than build a broken "http://null:.." link.
        val json = peersJson(listOf(peer("beta_ha_paneld", name = "Beta", ip = null)))
        assertTrue(json, json.contains(""""ip":null"""))
        assertTrue(json, json.contains(""""url":null"""))
        assertFalse("a null ip must never be quoted", json.contains(""""ip":"null""""))
    }

    @Test fun peersJsonSelfIsABooleanNotAString() {
        val json = peersJson(listOf(peer("me", name = "Me", self = true)))
        assertTrue(json, json.contains(""""self":true"""))
        assertFalse("self must be a JSON boolean", json.contains(""""self":"true""""))
    }

    @Test fun peersJsonEscapesNameAndPanelId() {
        // A friendly name is user/peer-supplied — a quote must be escaped, not break the JSON.
        val json = peersJson(listOf(peer("p", name = "A \"quoted\" panel")))
        assertTrue(json, json.contains("""A \"quoted\" panel"""))
    }
}
