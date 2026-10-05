package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaMdnsIdentityTest {
    private val firstUuid = "00112233445566778899aabbccddeeff"
    private val secondUuid = "ffeeddccbbaa99887766554433221100"

    @Test fun canonicalOriginNormalizesCaseAndDefaultPorts() {
        assertEquals("http://ha.local", canonicalHaOrigin("HTTP://HA.LOCAL:80/lovelace/home?x=1"))
        assertEquals("https://ha.example", canonicalHaOrigin("https://HA.example:443/anything"))
        assertNull(canonicalHaOrigin("ha.local:8123"))
        assertNull(canonicalHaOrigin("ftp://ha.local"))
    }

    @Test fun parserRejectsMalformedIdentityOrAdvertisedUrl() {
        assertNull(parseHaTxtRecord("not-a-core-uuid", "http://ha.local:8123", null, null, null))
        assertNull(parseHaTxtRecord(firstUuid, "not a url", null, null, null))
        assertNull(parseHaTxtRecord(firstUuid, "http://user:pass@ha.local:8123", null, null, null))
    }

    @Test fun exactAdvertisedOriginSelectsOneUuid() {
        val record = requireNotNull(parseHaTxtRecord(
            firstUuid, "http://ha.local:8123", "https://ha.example.net", null, "$firstUuid.local.",
        ))
        assertEquals(firstUuid, matchHaInstanceUuid(listOf("http://HA.local:8123/lovelace/alpha"), listOf(record)))
        assertEquals(firstUuid, matchHaInstanceUuid(listOf("https://ha.example.net"), listOf(record)))
    }

    @Test fun uuidLocalHostnameIsAnExactIdentityAlias() {
        val record = requireNotNull(parseHaTxtRecord(firstUuid, null, null, null, "$firstUuid.local."))
        assertEquals(firstUuid, matchHaInstanceUuid(listOf("http://$firstUuid.local:8123"), listOf(record)))
    }

    @Test fun unmatchedAndAmbiguousRecordsFailClosed() {
        val first = requireNotNull(parseHaTxtRecord(firstUuid, "http://ha.local:8123", null, null, null))
        val second = requireNotNull(parseHaTxtRecord(secondUuid, "http://ha.local:8123", null, null, null))

        assertNull(matchHaInstanceUuid(listOf("http://different.local:8123"), listOf(first)))
        assertNull(matchHaInstanceUuid(listOf("http://ha.local:8123"), listOf(first, second)))
        assertNull(matchHaInstanceUuid(listOf("not a url"), listOf(first)))
    }

    @Test fun duplicateAdvertisementsForTheSameUuidAreNotAmbiguous() {
        val internal = requireNotNull(parseHaTxtRecord(firstUuid, "http://ha.local:8123", null, null, null))
        val external = requireNotNull(parseHaTxtRecord(firstUuid, null, "https://ha.example.net", null, null))
        assertEquals(firstUuid, matchHaInstanceUuid(
            listOf("http://ha.local:8123", "https://ha.example.net"), listOf(internal, external),
        ))
    }
}
