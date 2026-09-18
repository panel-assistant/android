package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdaterAssetMatchTest {
    private val bridgeApk = "ha-paneld-v0.9.8-manual-setup-required.apk"
    private val successorApk = "panel-assistant-v0.9.8-manual-setup-required.apk"
    private val assets = listOf("install-descriptor.json", successorApk, bridgeApk, "$bridgeApk.idsig")

    @Test fun eachBuildFollowsItsOwnAssetWhateverTheReleaseOrder() {
        assertEquals(bridgeApk, assets.first(SelfUpdater.ownAssetMatch(bridge = true)))
        assertEquals(successorApk, assets.first(SelfUpdater.ownAssetMatch(bridge = false)))
    }

    @Test fun theBridgeStillTakesTheOnlyApkOfAnEarlierRelease() {
        assertTrue(SelfUpdater.isBridgeAsset("ha-paneld-v0.9.7-manual-setup-required.apk"))
        assertTrue(SelfUpdater.isBridgeAsset("ANYTHING.APK"))
        assertFalse(SelfUpdater.isBridgeAsset("ha-paneld-v0.9.7.apk.idsig"))
    }

    @Test fun onlyThePrefixedApkIsTheSuccessor() {
        assertTrue(SelfUpdater.isSuccessorAsset(successorApk))
        assertFalse(SelfUpdater.isSuccessorAsset(bridgeApk))
        assertFalse(SelfUpdater.isSuccessorAsset("panel-assistant-v0.9.8.apk.idsig"))
        assertFalse(SelfUpdater.isSuccessorAsset("x-panel-assistant-v0.9.8.apk"))
    }
}
