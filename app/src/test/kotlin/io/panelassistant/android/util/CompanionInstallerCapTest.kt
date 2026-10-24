package io.panelassistant.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure version-cap decision for the Companion auto-updater (see CompanionInstaller.exceedsCap). */
class CompanionInstallerCapTest {
    @Test fun releaseNotesAreConfinedToGithubWithoutLosingTheInstallTarget() {
        val allowed = "https://github.com/home-assistant/android/releases/tag/2026.6.5"
        for (notes in listOf("javascript:x", "http://github.com/x", "https://example.org/x",
            "https://github.com.evil.example/x", "https://github.com@evil.example/x", "/relative", "https://[", allowed)) {
            val release = org.json.JSONObject().put("tag_name", "2026.6.5").put("html_url", notes)
                .put("assets", org.json.JSONArray().put(org.json.JSONObject()
                    .put("name", "app-minimal-release.apk").put("browser_download_url", "https://github.com/home-assistant/android/releases/download/2026.6.5/app.apk")))
            val versions = ReleaseCatalog.select(listOf(ReleaseCatalog.raw(release) { it == "app-minimal-release.apk" }), "stable", 1) { it }
            val target = CompanionInstaller.chooseTarget(versions, null)!!
            assertEquals("2026.6.5", target.version)
            assertTrue(target.apkUrl.endsWith("app.apk"))
            assertEquals("notes $notes", if (notes == allowed) allowed else "", target.releaseUrl)
        }
    }

    @Test fun noCapNeverExceeds() {
        assertFalse(CompanionInstaller.exceedsCap("2026.6.5", null))
        assertFalse(CompanionInstaller.exceedsCap("9999.1.0", null))
    }

    @Test fun aboveCapExceeds() {
        assertTrue(CompanionInstaller.exceedsCap("2026.6.5", "2026.5.4"))
        assertTrue(CompanionInstaller.exceedsCap("2026.6.0", "2026.5.4"))
        assertTrue(CompanionInstaller.exceedsCap("2027.1.0", "2026.5.4"))
    }

    @Test fun atOrBelowCapDoesNotExceed() {
        assertFalse(CompanionInstaller.exceedsCap("2026.5.4", "2026.5.4"))   // equal
        assertFalse(CompanionInstaller.exceedsCap("2026.5.3", "2026.5.4"))
        assertFalse(CompanionInstaller.exceedsCap("2026.4.9", "2026.5.4"))
    }

    @Test fun stripsVariantSuffixBeforeComparing() {
        // The release version carries a "-minimal"/"-full" variant tag that must not defeat the compare.
        assertFalse(CompanionInstaller.exceedsCap("2026.5.4-minimal", "2026.5.4"))
        assertTrue(CompanionInstaller.exceedsCap("2026.6.5-full", "2026.5.4"))
    }

    @Test fun malformedVersionFailsClosedUnderCap() {
        assertTrue(CompanionInstaller.exceedsCap("not-a-version", "2026.5.4"))
        assertFalse(CompanionInstaller.withinCap("not-a-version", "2026.5.4"))
    }

    @Test fun exactVersionPickerCannotBypassSafetyCap() {
        assertNull(CompanionInstaller.exactVersionRefusal("2026.5.4-minimal", "2026.5.4"))
        assertTrue(CompanionInstaller.exactVersionRefusal("2026.6.5-minimal", "2026.5.4")!!.startsWith("refused:"))
        assertTrue(CompanionInstaller.exactVersionRefusal("not-a-version", "2026.5.4")!!.startsWith("refused:"))
    }

    @Test fun unsafeInstalledBuildRequiresExplicitDowngradeButOrdinaryOlderTargetIsNotInstalled() {
        assertFalse(CompanionInstaller.shouldInstallTarget("2026.6.5-minimal", "2026.5.4", force = false, maxVersion = "2026.5.4"))
        assertTrue(CompanionInstaller.shouldInstallTarget("2026.6.5-minimal", "2026.5.4", force = true, maxVersion = "2026.5.4"))
        assertFalse(CompanionInstaller.shouldInstallTarget("2026.5.4-minimal", "2026.5.3", force = false, maxVersion = "2026.5.4"))
        assertTrue(CompanionInstaller.shouldInstallTarget("2026.5.4-minimal", "2026.5.3", force = true, maxVersion = "2026.5.4"))
    }

    @Test fun cappedTargetUsesNewestCompleteReleaseInsideCeiling() {
        val versions = listOf(
            version("2026.6.5", "u665"),
            version("2026.5.4", "u554"),
            version("2026.5.3", "u553"),
        )
        val target = CompanionInstaller.chooseTarget(versions, "2026.5.4")
        assertEquals("2026.5.4", target?.version)
        assertEquals("u554", target?.apkUrl)
        assertEquals("2026.6.5", target?.newestVersion)
        assertTrue(target?.capped == true)
    }

    @Test fun uncappedHeadWithoutApkDoesNotSilentlySelectOlderRelease() {
        val versions = listOf(version("2026.6.5", null), version("2026.5.4", "u554"))
        assertNull(CompanionInstaller.chooseTarget(versions, null))
    }

    @Test fun agedOutSafetyCapResolvesItsExactRelease() {
        val versions = listOf(version("2027.2.0", "u720"), version("2027.1.0", "u710"))
        val lookedUp = mutableListOf<String>()
        val target = CompanionInstaller.chooseTargetWithExact(versions, "2026.5.4") { tag ->
            lookedUp += tag
            if (tag == "2026.5.4") "u-exact-654" else null
        }

        assertEquals(listOf("2026.5.4"), lookedUp)
        assertEquals("2026.5.4", target?.version)
        assertEquals("u-exact-654", target?.apkUrl)
        assertEquals("2027.2.0", target?.newestVersion)
        assertTrue(target?.capped == true)
    }

    @Test fun committedPresentationIsClassifiedFromVersionStateNotEnglishProse() {
        assertEquals(
            "managed-install-committed",
            CompanionInstaller.committedCode("", "2026.5.4"),
        )
        assertEquals(
            "managed-update-committed",
            CompanionInstaller.committedCode("2026.5.3-minimal", "2026.5.4"),
        )
        assertEquals(
            "managed-update-committed",
            CompanionInstaller.committedCode("2026.5.4-minimal", "2026.5.4"),
        )
        assertEquals(
            "managed-downgrade-committed",
            CompanionInstaller.committedCode("2026.6.5-minimal", "2026.5.4"),
        )
    }

    private fun version(version: String, apk: String?) = ReleaseCatalog.Version(
        version = version,
        tag = version,
        notesUrl = "notes-$version",
        installable = apk != null,
        apkUrl = apk,
    )
}
