package io.panelassistant.android.http

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
            overscrollChanged = true,
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
            overscrollChanged = false,
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
            overscrollChanged = false,
            darkMode = null,
        )

        assertFalse(effects.reloadBuiltin)
        assertTrue(effects.relaunchBuiltin)
    }

    @Test fun nativeKioskChangeNeedsForegroundRelaunchWithoutReload() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("dashboard_native_kiosk" to "false"),
            accepted = mapOf("dashboard_native_kiosk" to "true"),
        )

        assertFalse(effects.reloadBuiltin)
        assertTrue(effects.relaunchBuiltin)
    }

    @Test fun importedExplicitAccessTokenReloadsWhenItDropsAnOldRefreshSession() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("ha_token" to "same-access", "ha_refresh_token" to "old-refresh"),
            accepted = mapOf("ha_token" to "same-access"),
        )

        assertTrue(effects.reloadBuiltin)
    }

    @Test fun trailingSlashOnlyHaUrlDifferenceDoesNotReload() {
        val effects = RendererConfigEffects.between(
            previous = mapOf("ha_url" to "http://ha:8123"),
            accepted = mapOf("ha_url" to "http://ha:8123/"),
        )

        assertFalse(effects.reloadBuiltin)
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

    @Test fun aHomeDashboardChangeReloadsTheBuiltInRenderer() {
        // Editing the home dashboard used to update config without navigating the renderer, so it looked
        // like nothing happened. A home change now reloads the built-in renderer onto the new path.
        val effects = RendererConfigEffects.between(
            mapOf("home_dashboard" to "/lovelace/0"),
            mapOf("home_dashboard" to "/kiosk/wall"),
        )
        assertTrue(effects.reloadBuiltin)
        assertFalse(effects.dashboardChanged)
    }
}
