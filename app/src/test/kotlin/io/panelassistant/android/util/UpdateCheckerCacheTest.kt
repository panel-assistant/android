package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class UpdateCheckerCacheTest {
    private val stable = UpdateChecker.RequestedPolicies(
        paneldChannel = "stable",
        companion = UpdateChecker.CompanionPolicy("stable", "2026.5.4"),
    )
    private val paneldUpdate = UpdateChecker.UpdateInfo("ha-paneld", "0.9.1", "0.9.2", "paneld-url", "paneld")
    private val companionUpdate = UpdateChecker.UpdateInfo(
        "HA Companion",
        "2026.5.3",
        "2026.5.4",
        "companion-url",
        "companion",
    )

    @Test fun updateInfoAddsStableComponentWithoutChangingLegacyFields() {
        assertEquals("paneld", paneldUpdate.component)
        assertEquals("companion", companionUpdate.component)
        assertEquals("ha-paneld", paneldUpdate.label)
        assertEquals("HA Companion", companionUpdate.label)

        val legacy = UpdateChecker.UpdateInfo("ha-paneld", "1", "2", "url")
        assertEquals("", legacy.component)
        assertEquals("ha-paneld", legacy.label)
    }

    @Test fun bothResolvedReplaceTheCacheAndCompleteTheTransaction() {
        val result = reconcile(
            previous = emptyList(),
            paneldResolution = UpdateChecker.Resolution.Resolved(paneldUpdate),
            companionResolution = UpdateChecker.Resolution.Resolved(companionUpdate),
        )

        assertEquals(listOf(paneldUpdate, companionUpdate), result.available)
        assertEquals("stable", result.paneldCacheChannel)
        assertEquals(stable.companion, result.companionCachePolicy)
        assertTrue(result.complete)
    }

    @Test fun samePolicyPaneldFailurePreservesOnlyPaneldWhileCompanionResolves() {
        val result = reconcile(
            previous = listOf(paneldUpdate, companionUpdate),
            paneldResolution = UpdateChecker.Resolution.Failed,
            companionResolution = UpdateChecker.Resolution.Resolved(null),
        )

        assertEquals(listOf(paneldUpdate), result.available)
        assertFalse(result.complete)
    }

    @Test fun samePolicyCompanionFailurePreservesOnlyCompanionWhilePaneldResolves() {
        val result = reconcile(
            previous = listOf(paneldUpdate, companionUpdate),
            paneldResolution = UpdateChecker.Resolution.Resolved(null),
            companionResolution = UpdateChecker.Resolution.Failed,
        )

        assertEquals(listOf(companionUpdate), result.available)
        assertFalse(result.complete)
    }

    @Test fun paneldChannelChangeCannotPreserveAnOldChannelResult() {
        val result = UpdateChecker.reconcileCache(
            previous = listOf(paneldUpdate, companionUpdate),
            requested = stable.copy(paneldChannel = "prerelease"),
            paneldCachedChannel = "stable",
            companionCachedPolicy = stable.companion,
            paneldResolution = UpdateChecker.Resolution.Failed,
            companionResolution = UpdateChecker.Resolution.Resolved(companionUpdate),
        )

        assertEquals(listOf(companionUpdate), result.available)
        assertEquals("stable", result.paneldCacheChannel)
        assertFalse(result.complete)
    }

    @Test fun companionChannelOrCapChangeCannotPreserveAnOldPolicyResult() {
        listOf(
            UpdateChecker.CompanionPolicy("prerelease", stable.companion.maxVersion),
            UpdateChecker.CompanionPolicy("stable", "2026.4.3"),
        ).forEach { changedPolicy ->
            val result = UpdateChecker.reconcileCache(
                previous = listOf(paneldUpdate, companionUpdate),
                requested = stable.copy(companion = changedPolicy),
                paneldCachedChannel = "stable",
                companionCachedPolicy = stable.companion,
                paneldResolution = UpdateChecker.Resolution.Resolved(paneldUpdate),
                companionResolution = UpdateChecker.Resolution.Failed,
            )

            assertEquals(listOf(paneldUpdate), result.available)
            assertEquals(stable.companion, result.companionCachePolicy)
            assertFalse(result.complete)
        }
    }

    @Test fun absentCompanionAuthoritativelyClearsItsEntryAndCompletes() {
        val result = reconcile(
            previous = listOf(paneldUpdate, companionUpdate),
            paneldResolution = UpdateChecker.Resolution.Resolved(paneldUpdate),
            companionResolution = UpdateChecker.Resolution.Resolved(null),
        )

        assertEquals(listOf(paneldUpdate), result.available)
        assertEquals(stable.companion, result.companionCachePolicy)
        assertTrue(result.complete)
    }

    @Test fun panelAssistantProjectionRetainsOnlyTheCachedStableExactTarget() {
        val json = JSONObject(
            UpdateChecker.panelAssistantUpdateJson(
                listOf(
                    UpdateChecker.UpdateInfo(
                        "ha-paneld",
                        "0.9.7-rc3",
                        "0.9.7",
                        "https://release.example/private-details",
                        "paneld",
                        "v0.9.7",
                    ),
                    companionUpdate,
                ),
            ),
        )

        assertEquals("available", json.getString("state"))
        assertEquals("0.9.7-rc3", json.getString("current_version"))
        assertEquals("0.9.7", json.getString("target_version"))
        assertEquals("v0.9.7", json.getString("tag"))
        assertFalse(json.toString().contains("release.example"))
        assertEquals(
            setOf("state", "current_version", "target_version", "tag"),
            json.keys().asSequence().toSet(),
        )
    }

    @Test fun panelAssistantProjectionUsesExplicitAbsenceForUnsafeOrUnsupportedCache() {
        val unsupported = listOf(
            // A prerelease target must not become a stable update offer.
            UpdateChecker.UpdateInfo("ha-paneld", "0.9.7-rc3", "0.9.8-rc1", "url", "paneld", "v0.9.8-rc1"),
            // A duplicate component makes the cache ambiguous rather than allowing a first-entry choice.
            UpdateChecker.UpdateInfo("ha-paneld", "0.9.7", "0.9.8", "url", "paneld", "v0.9.8"),
        )
        val absent = JSONObject(UpdateChecker.panelAssistantUpdateJson(unsupported))
        assertEquals("none", absent.getString("state"))
        assertEquals(setOf("state"), absent.keys().asSequence().toSet())

        val missingTag = UpdateChecker.panelAssistantUpdate(
            listOf(UpdateChecker.UpdateInfo("ha-paneld", "0.9.7", "0.9.8", "url", "paneld")),
        )
        assertNull(missingTag)
        assertNull(
            UpdateChecker.panelAssistantUpdate(
                listOf(UpdateChecker.UpdateInfo("ha-paneld", "0.9.7", "0.9.8", "url", "paneld", "v0.9.9")),
            ),
        )
        assertNull(
            UpdateChecker.panelAssistantUpdate(
                listOf(
                    UpdateChecker.UpdateInfo(
                        "ha-paneld",
                        "0.9.7",
                        "0.9.8",
                        "url",
                        "paneld",
                        "v0.9.8",
                        prerelease = true,
                    ),
                ),
            ),
        )
    }

    private fun reconcile(
        previous: List<UpdateChecker.UpdateInfo>,
        paneldResolution: UpdateChecker.Resolution,
        companionResolution: UpdateChecker.Resolution,
    ) = UpdateChecker.reconcileCache(
        previous = previous,
        requested = stable,
        paneldCachedChannel = "stable",
        companionCachedPolicy = stable.companion,
        paneldResolution = paneldResolution,
        companionResolution = companionResolution,
    )
}
