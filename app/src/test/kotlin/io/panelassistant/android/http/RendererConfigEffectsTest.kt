package io.panelassistant.android.http

import io.panelassistant.android.config.RendererApply
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.rendererReconcilesOnChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererConfigEffectsTest {
    @Test fun dashboardSwitchDominatesReloadAndRelaunchRequests() {
        val effects = RendererConfigEffects.coalesce(
            dashboardChanged = true,
            credentialChanged = true,
            zoomChanged = true,
            fullscreenChanged = true,
            darkMode = true,
        )

        assertTrue(effects.dashboardChanged)
        assertFalse(effects.reloadBuiltin)
        assertFalse(effects.relaunchBuiltin)
    }

    @Test fun reloadDominatesForegroundRelaunch() {
        val effects = RendererConfigEffects.coalesce(
            dashboardChanged = false,
            credentialChanged = false,
            zoomChanged = true,
            fullscreenChanged = true,
            darkMode = null,
        )

        assertTrue(effects.reloadBuiltin)
        assertFalse(effects.relaunchBuiltin)
    }

    @Test fun fullscreenOnlyChangeNeedsForegroundRelaunch() {
        val effects = RendererConfigEffects.coalesce(
            dashboardChanged = false,
            credentialChanged = false,
            zoomChanged = false,
            fullscreenChanged = true,
            darkMode = null,
        )

        assertFalse(effects.reloadBuiltin)
        assertTrue(effects.relaunchBuiltin)
    }

    /** Only a lone home change may wait for the setup question's answer; a credential with it still relaunches. */
    @Test fun aHomeChangeIsHomeOnlyUnlessSomethingElseNeedsTheRelaunch() {
        val home = RendererConfigEffects.between(mapOf("home_dashboard" to ""), mapOf("home_dashboard" to "/home"))
        assertTrue(home.relaunchBuiltin)
        assertTrue(home.relaunchForHomeOnly)
        val homeAndCredential = RendererConfigEffects.between(
            mapOf("home_dashboard" to "", "ha_token" to ""),
            mapOf("home_dashboard" to "/home", "ha_token" to "fresh"),
        )
        assertTrue(homeAndCredential.relaunchBuiltin)
        assertFalse(homeAndCredential.relaunchForHomeOnly)
    }

    @Test fun nativeKioskChangeNeedsForegroundRelaunchWithoutReload() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("dashboard_native_kiosk" to "false"),
            accepted = mapOf("dashboard_native_kiosk" to "true"),
        )

        assertFalse(effects.reloadBuiltin)
        assertTrue(effects.relaunchBuiltin)
    }

    @Test fun importedExplicitAccessTokenReachesTheRendererWhenItDropsAnOldRefreshSession() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("ha_token" to "same-access", "ha_refresh_token" to "old-refresh"),
            accepted = mapOf("ha_token" to "same-access"),
        )

        assertTrue(effects.relaunchBuiltin)
    }

    @Test fun trailingSlashOnlyHaUrlDifferenceDoesNotReload() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("ha_url" to "http://ha:8123"),
            accepted = mapOf("ha_url" to "http://ha:8123/"),
        )

        assertFalse(effects.reloadBuiltin)
        assertFalse(effects.relaunchBuiltin)
    }

    @Test fun sameUrlCredentialRepairsAreTargetChanges() {
        val previous = mapOf(
            "ha_url" to "http://ha:8123",
            "ha_token" to "old-token",
            "ha_refresh_token" to "old-refresh",
            "ha_token_expiry" to "100",
            "ha_client_id" to "old-client",
        )

        assertTrue(RendererConfigEffects.credentialsChanged(previous, mapOf("ha_token" to "new-token")))
        assertTrue(RendererConfigEffects.credentialsChanged(previous, mapOf("ha_refresh_token" to "new-refresh")))
        assertTrue(RendererConfigEffects.credentialsChanged(previous, mapOf("ha_token_expiry" to "200")))
        assertTrue(RendererConfigEffects.credentialsChanged(previous, mapOf("ha_client_id" to "new-client")))
        assertFalse(RendererConfigEffects.credentialsChanged(previous, mapOf("ha_url" to "http://ha:8123/")))
    }

    @Test fun aCallerThatOwnsTheCredentialGroupAndHomePathDecidesTheirEffects() {
        // The direct Configure save takes these from the credential group's owner and from the home path
        // its live handler actually applied, not from comparing the posted strings.
        val previous = mapOf("ha_token" to "old", "home_dashboard" to "/lovelace/0")
        val accepted = mapOf("ha_token" to "new", "home_dashboard" to "/kiosk/wall")
        assertTrue(RendererConfigEffects.between(previous, accepted).relaunchBuiltin)
        assertFalse(
            RendererConfigEffects.between(previous, accepted, credentialChanged = false, homeChanged = false).relaunchBuiltin,
        )
        assertTrue(RendererConfigEffects.between(emptyMap(), emptyMap(), credentialChanged = true).relaunchBuiltin)
    }

    @Test fun aHomeDashboardChangeBringsTheBuiltInRendererForward() {
        // Editing the home dashboard used to update config without navigating the renderer, so it looked
        // like nothing happened. A home change now brings the built-in renderer forward, which re-admits
        // itself onto the new path; a reload on top would load it twice.
        val effects = RendererConfigEffects.between(
            mapOf("home_dashboard" to "/lovelace/0"),
            mapOf("home_dashboard" to "/kiosk/wall"),
        )
        assertTrue(effects.relaunchBuiltin)
        assertFalse(effects.reloadBuiltin)
        assertFalse(effects.dashboardChanged)
    }

    /**
     * The settings' own definitions say when the renderer shows a change; a committed change must reach it
     * that way. Every setting it reconciles itself is brought forward without a reload, every setting the
     * next page load carries is reloaded, and a new setting declared either way is covered without a list
     * to keep here. (The JVM reports Android SDK 0, so dark mode is the reload it is below Android 10.)
     */
    @Test fun everyRendererSettingACommitChangesReachesTheRendererAsItsDefinitionSays() {
        SettingsRegistry.SPECS.forEach { spec ->
            val (before, after) = when (spec.type) {
                SettingType.BOOL -> "false" to "true"
                SettingType.INT, SettingType.LONG, SettingType.FLOAT -> "100" to "120"
                else -> "/before" to "/after"
            }
            val effects = RendererConfigEffects.between(mapOf(spec.key to before), mapOf(spec.key to after))
            when {
                SettingsRegistry.rendererApply(spec.key, 0) == RendererApply.RELOAD ->
                    assertTrue("${spec.key} reloads", effects.reloadBuiltin)
                rendererReconcilesOnChange(spec.key, 0) -> {
                    assertTrue("${spec.key} brings the renderer forward", effects.relaunchBuiltin)
                    assertFalse("${spec.key} does not reload on top of the renderer's own rebuild", effects.reloadBuiltin)
                }
            }
        }
    }
}
