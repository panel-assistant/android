package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckerCacheTest {
    private val stable = UpdateChecker.CompanionPolicy("stable", "2026.5.4")
    private val companionUpdate = UpdateChecker.UpdateInfo(
        "HA Companion",
        "2026.5.3",
        "2026.5.4",
        "companion-url",
        "companion",
    )

    @Test fun updateInfoAddsStableComponentWithoutChangingLegacyFields() {
        assertEquals("companion", companionUpdate.component)
        assertEquals("HA Companion", companionUpdate.label)

        val legacy = UpdateChecker.UpdateInfo("HA Companion", "1", "2", "url")
        assertEquals("", legacy.component)
        assertEquals("HA Companion", legacy.label)
    }

    @Test fun aResolvedLookupReplacesTheCache() {
        val result = reconcile(previous = emptyList(), UpdateChecker.Resolution.Resolved(companionUpdate))

        assertEquals(listOf(companionUpdate), result.available)
        assertEquals(stable, result.companionCachePolicy)
    }

    @Test fun samePolicyFailurePreservesTheCachedUpdate() {
        val result = reconcile(previous = listOf(companionUpdate), UpdateChecker.Resolution.Failed)

        assertEquals(listOf(companionUpdate), result.available)
        assertEquals(stable, result.companionCachePolicy)
    }

    @Test fun companionChannelOrCapChangeCannotPreserveAnOldPolicyResult() {
        listOf(
            UpdateChecker.CompanionPolicy("prerelease", stable.maxVersion),
            UpdateChecker.CompanionPolicy("stable", "2026.4.3"),
        ).forEach { changedPolicy ->
            val result = UpdateChecker.reconcileCache(
                previous = listOf(companionUpdate),
                requested = changedPolicy,
                companionCachedPolicy = stable,
                companionResolution = UpdateChecker.Resolution.Failed,
            )

            assertEquals(emptyList<UpdateChecker.UpdateInfo>(), result.available)
            assertEquals(stable, result.companionCachePolicy)
        }
    }

    @Test fun absentCompanionAuthoritativelyClearsItsEntry() {
        val result = reconcile(previous = listOf(companionUpdate), UpdateChecker.Resolution.Resolved(null))

        assertEquals(emptyList<UpdateChecker.UpdateInfo>(), result.available)
        assertEquals(stable, result.companionCachePolicy)
    }

    private fun reconcile(previous: List<UpdateChecker.UpdateInfo>, resolution: UpdateChecker.Resolution) =
        UpdateChecker.reconcileCache(
            previous = previous,
            requested = stable,
            companionCachedPolicy = stable,
            companionResolution = resolution,
        )
}
