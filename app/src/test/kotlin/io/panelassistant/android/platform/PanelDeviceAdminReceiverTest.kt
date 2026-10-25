package io.panelassistant.android.platform

import android.content.ContextWrapper
import android.content.Intent
import io.panelassistant.android.Config
import io.panelassistant.android.control.FakeScreenPower
import io.panelassistant.android.fakePreferences
import io.panelassistant.android.setupEverCompleted
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelDeviceAdminReceiverTest {
    @Test fun anInstallerClaimCannotBlockUninstallDuringAnUnfinishedFirstRun() {
        val prefs = fakePreferences(mapOf(
            "device_local_setup_identity_confirmed" to true,
            "ha_url" to "http://ha.local:8123",
        ))
        val config = Config(prefs.instance)
        val power = FakeScreenPower().apply { adminActive = true }

        PanelDeviceAdminReceiver({ config }, { power }).onEnabled(ContextWrapper(null), Intent())

        assertFalse(config.setupEverCompleted)
        assertFalse(power.isDeviceAdminActive())
        assertFalse(power.lockNow())
    }

    @Test fun startupReleasesAnAdminLeftByAnAbandonedInstallation() {
        val config = Config(fakePreferences().instance)
        val power = FakeScreenPower().apply { adminActive = true }

        reconcileSetupDeviceAdmin(config, power)

        assertFalse(config.setupEverCompleted)
        assertFalse(power.isDeviceAdminActive())
    }

    @Test fun aCompletedPanelKeepsItsRootlessScreenLockWhileItsConnectionNeedsRepair() {
        val config = Config(fakePreferences().instance).apply { setupEverCompleted = true }
        val power = FakeScreenPower().apply { adminActive = true }

        PanelDeviceAdminReceiver({ config }, { power }).onEnabled(ContextWrapper(null), Intent())
        reconcileSetupDeviceAdmin(config, power)

        assertTrue(power.isDeviceAdminActive())
        assertTrue(power.lockNow())
    }

    @Test fun aConfiguredLegacyPanelMigratesBeforeItsAdminEligibilityIsDecided() {
        val config = Config(fakePreferences(mapOf("ha_url" to "http://ha.local:8123")).instance)
        val power = FakeScreenPower().apply { adminActive = true }

        PanelDeviceAdminReceiver({ config }, { power }).onEnabled(ContextWrapper(null), Intent())
        reconcileSetupDeviceAdmin(config, power)

        assertTrue(config.setupEverCompleted)
        assertTrue(power.isDeviceAdminActive())
        assertTrue(power.lockNow())
    }
}
