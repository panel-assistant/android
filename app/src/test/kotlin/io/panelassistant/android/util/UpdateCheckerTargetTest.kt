package io.panelassistant.android.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The always-present, policy-keyed catalog targets behind the MQTT update entities. */
class UpdateCheckerTargetTest {
    private val companion = UpdateChecker.ResolvedTarget(
        version = "2026.5.4",
        tag = "2026.5.4",
        releaseUrl = "https://github.com/home-assistant/android/releases/tag/2026.5.4",
        channel = "stable",
        cap = "2026.5.4",
        capped = true,
        newestVersion = "2026.9.1",
    )

    @Test fun aTargetIsNeverReusedUnderAnotherChannelOrCap() {
        assertEquals(companion, UpdateChecker.samePolicy(companion, "stable", "2026.5.4"))
        assertNull(UpdateChecker.samePolicy(companion, "prerelease", "2026.5.4"))
        assertNull(UpdateChecker.samePolicy(companion, "stable", null))
        assertNull(UpdateChecker.samePolicy(companion, "stable", "2026.6.0"))
        assertNull(UpdateChecker.samePolicy(null, "stable", "2026.5.4"))
        val paneld = companion.copy(cap = null, capped = false, newestVersion = null)
        assertEquals(paneld, UpdateChecker.samePolicy(paneld, "stable", null))
        assertNull(UpdateChecker.samePolicy(paneld, "stable", "2026.5.4"))
    }

    @Test fun rememberedPanelAppOffersCannotRegainUpdateAuthorityAfterRestart() {
        val paneld = UpdateChecker.ResolvedTarget(
            "1.1.0", "v1.1.0", "https://github.com/panel-assistant/android/releases/tag/v1.1.0", "stable", null,
        )
        UpdateChecker.restoreTargets(UpdateChecker.encodeTarget(paneld), "")
        assertNull(UpdateChecker.paneldTarget("stable"))
        assertEquals("", UpdateChecker.persistableTargets().first)
    }

    @Test fun persistedTargetsRoundTrip() {
        assertEquals(companion, UpdateChecker.decodeTarget(UpdateChecker.encodeTarget(companion)))
        val paneld = UpdateChecker.ResolvedTarget(
            "0.9.8", "v0.9.8", "https://github.com/panel-assistant/android/releases/tag/v0.9.8", "prerelease", null,
        )
        assertEquals(paneld, UpdateChecker.decodeTarget(UpdateChecker.encodeTarget(paneld)))
    }

    @Test fun persistedTargetsAreRevalidatedAsStrictlyAsAFreshResolution() {
        val good = JSONObject(UpdateChecker.encodeTarget(companion))
        fun with(key: String, value: Any?) = JSONObject(good.toString()).put(key, value ?: JSONObject.NULL).toString()
        for ((label, raw) in listOf(
            "blank" to "",
            "not json" to "{",
            "array" to "[]",
            "bad tag" to with("tag", "../evil"),
            "numeric tag" to with("tag", 2026),
            "missing tag" to JSONObject(good.toString()).apply { remove("tag") }.toString(),
            "plain http" to with("release_url", "http://github.com/x"),
            "javascript url" to with("release_url", "javascript:alert(1)"),
            "odd channel" to with("channel", "beta"),
            "junk version" to with("version", "latest"),
            "oversized" to with("newest", "x".repeat(5_000)),
        )) {
            assertNull(label, UpdateChecker.decodeTarget(raw))
        }
    }

    @Test fun theUpdateEntityReleaseLinkStaysInsideTheReleasesPath() {
        assertEquals("https://github.com/panel-assistant/android/releases/tag/v0.9.8", SelfUpdater.releaseNotesUrl("v0.9.8"))
        assertNull(SelfUpdater.releaseNotesUrl("../../evil"))
        assertNull(SelfUpdater.releaseNotesUrl("v0.9.8?x=1"))
    }
}
