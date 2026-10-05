package io.panelassistant.android.util

import io.panelassistant.android.util.ReleaseCatalog.Raw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ReleaseCatalogTest {
    // newest-first, as the GitHub releases list returns them
    private val raw = listOf(
        Raw("v0.8.6", false, "n6", "u6"),
        Raw("v0.8.6-rc4", true, "n5", "u5"),
        Raw("v0.8.5", false, "n4", "u4"),
        Raw("v0.8.5-rc1", true, "n3", null), // no APK asset → not installable
    )
    private val strip: (String) -> String = { it.removePrefix("v") }

    @Test fun stableChannelDropsPrereleases() {
        val v = ReleaseCatalog.select(raw, "stable", 10, strip)
        assertEquals(listOf("0.8.6", "0.8.5"), v.map { it.version })
        assertTrue(v.all { it.installable })
    }

    @Test fun prereleaseChannelKeepsEverythingNewestFirst() {
        val v = ReleaseCatalog.select(raw, "prerelease", 10, strip)
        assertEquals(listOf("0.8.6", "0.8.6-rc4", "0.8.5", "0.8.5-rc1"), v.map { it.version })
    }

    @Test fun limitCapsTheList() {
        assertEquals(2, ReleaseCatalog.select(raw, "prerelease", 2, strip).size)
    }

    @Test fun stablePagesStayInsideTheResponseBound() {
        // The Companion target resolver asks for 40 stable releases; 100 per page measured 2.5 MB.
        assertEquals(50, ReleaseCatalog.pageSize("stable", 40))
        assertEquals(50, ReleaseCatalog.pageSize("stable", 10))
        assertEquals(40, ReleaseCatalog.pageSize("stable", 5))
        assertEquals(40, ReleaseCatalog.pageSize("prerelease", 40))
    }

    @Test fun missingApkAssetIsNotInstallable() {
        val v = ReleaseCatalog.select(raw, "prerelease", 10, strip)
        assertFalse(v.first { it.tag == "v0.8.5-rc1" }.installable)
    }

    @Test fun tagValidationRejectsPathTricks() {
        assertTrue(ReleaseCatalog.validTag("v0.8.6-rc4"))
        assertTrue(ReleaseCatalog.validTag("2026.6.5-minimal"))
        assertFalse(ReleaseCatalog.validTag("../../etc"))
        assertFalse(ReleaseCatalog.validTag("v1 0"))
        assertFalse(ReleaseCatalog.validTag(""))
    }

    @Test fun newestKeepsTagAndAssetFromOneRelease() {
        val selected = ReleaseCatalog.newest(raw, "prerelease")
        assertEquals("v0.8.6", selected?.tag)
        assertEquals("u6", selected?.apkUrl)
    }

    @Test fun apkTargetRetainsTheExactSourceTagBesideTheNormalisedVersion() {
        val target = ReleaseCatalog.apkTarget(Raw("v0.8.6", false, "n6", "u6"), strip)
        assertEquals("0.8.6", target?.version)
        assertEquals("v0.8.6", target?.tag)
        assertEquals("u6", target?.apkUrl)
        assertFalse(target?.prerelease ?: true)
        assertNull(ReleaseCatalog.apkTarget(Raw("v0.8.6", false, "n6", null), strip))
    }

    @Test fun apkTargetRetainsTheAuthoritativePrereleaseClassification() {
        val target = ReleaseCatalog.apkTarget(Raw("v0.8.7", true, "n7", "u7"), strip)
        assertEquals("0.8.7", target?.version)
        assertTrue(target?.prerelease == true)
    }

    @Test fun newestDoesNotFallBackPastAReleaseWithoutAnApk() {
        val missingHead = listOf(
            Raw("v0.8.7-rc1", true, "n7", null),
            Raw("v0.8.6", false, "n6", "u6"),
        )
        assertNull(ReleaseCatalog.newest(missingHead, "prerelease")?.apkUrl)
        assertEquals("v0.8.6", ReleaseCatalog.newest(missingHead, "stable")?.tag)
    }

    @Test fun apiResponseReaderEnforcesDeclaredAndStreamingBounds() {
        val payload = "{\"ok\":true}".toByteArray()
        assertEquals(
            "{\"ok\":true}",
            ReleaseCatalog.readResponse(ByteArrayInputStream(payload), payload.size.toLong(), 64L),
        )
        assertThrowsByteLimit {
            ReleaseCatalog.readResponse(ByteArrayInputStream(payload), 65L, 64L)
        }
        assertThrowsByteLimit {
            ReleaseCatalog.readResponse(ByteArrayInputStream(ByteArray(65)), -1L, 64L)
        }
    }

    @Test fun cappedPrereleasePickerSearchesPastBlockedPagesBeforeLimitingChoices() {
        val pages = mutableListOf<Int>()
        val versions = ReleaseCatalog.listPages("prerelease", 10, "2026.5.4", strip) { page, size ->
            pages += page
            assertEquals(50, size)
            if (page == 1) (1..40).map { Raw("2026.8.$it", true, "notes-$it", "unsafe-$it") } +
                (1..10).map { Raw("2026.4.$it", true, "missing-$it", null) }
            else (4 downTo 0).map { Raw("2026.5.$it", true, "safe-notes-$it", "safe-$it") }
        }
        assertEquals(listOf(1, 2), pages)
        assertEquals(6, versions.size)
        assertEquals("above_panel_limit", versions.first().unavailableReason)
        assertEquals("2026.5.4", versions.first().maxVersion)
        assertFalse(versions.first().installable)
        assertNull(versions.first().apkUrl)
        assertEquals("2026.5.4", versions[1].tag)
        assertEquals("safe-4", versions[1].apkUrl)
        assertTrue(versions.drop(1).all { it.installable })
    }

    @Test fun parsedReleaseDistinguishesOlderIdentityFromMissingCompatibleDownload() {
        val legacy = org.json.JSONObject("""{"tag_name":"v0.9.7","assets":[{"name":"ha-paneld-v0.9.7.apk","browser_download_url":"legacy"}]}""")
        val noAsset = org.json.JSONObject("""{"tag_name":"v0.9.9","assets":[]}""")
        val parsed = listOf(legacy, noAsset).map {
            ReleaseCatalog.raw(it, SelfUpdater::isSuccessorAsset) { name -> name.startsWith("ha-paneld-") && name.endsWith(".apk") }
        }
        val versions = ReleaseCatalog.select(parsed, "stable", 10, strip)
        assertEquals(listOf("older_app_id", "no_matching_asset"), versions.map { it.unavailableReason })
        assertTrue(versions.all { !it.installable && it.apkUrl == null })
    }

    private fun assertThrowsByteLimit(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected ByteLimitExceeded")
        } catch (_: ByteLimitExceeded) {
            // Expected.
        }
    }
}
