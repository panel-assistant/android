package io.panelassistant.android

import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dashboard can open before the service after an app update (Android 8.1 starts the HOME activity
 * first, and an install may start it explicitly). Its first page must still read migrated settings: a
 * schema-16 store that hid Home Assistant's navigation must not hide it on that page.
 */
internal class DashboardStartupMigrationTest {
    @Test fun theFirstPageAfterAnUpdateShowsNavigationAnOldStoreHid() {
        DashboardRendererRig(stored(schema = 16, kiosk = true)).use { rig ->
            val kiosk = firstPageKiosk(rig)
            assertEquals("store schema after dashboard startup", SettingsRegistry.SCHEMA, rig.store.values["config_schema"])
            assertNull("the first page must not hide navigation", kiosk)
        }
    }

    @Test fun anOwnersCurrentChoiceToHideNavigationSurvivesStartup() {
        DashboardRendererRig(stored(schema = SettingsRegistry.SCHEMA, kiosk = true)).use { rig ->
            assertEquals(ExternalBusController.CommandKind.KioskMode(true), firstPageKiosk(rig))
        }
    }

    private fun stored(schema: Int, kiosk: Boolean) =
        DashboardRendererRig.SIGNED_IN + mapOf("config_schema" to schema, SettingsRegistry.NATIVE_KIOSK_KEY to kiosk)

    /** Run the dashboard's startup read, open the first document with what it returned, and report the
     *  kiosk command that page sends Home Assistant once its frontend is up (null: none, navigation shown). */
    private fun firstPageKiosk(rig: DashboardRendererRig): ExternalBusController.CommandKind? {
        val prepare = DashboardActivity::class.java.getDeclaredMethod("prepareStartupConfig", Config::class.java)
            .apply { isAccessible = true }
        val config = prepare.invoke(rig.activity, Config(rig.store.instance)) as Config
        val page = RecordingWebView("${DashboardRendererRig.HA}/lovelace").also(rig::show)
        val session = rig.bridge.beginDocument(page, config, rig.get("rendererGeneration") as Long)
        val bus = DashboardFrontendBridge::class.java.getDeclaredField("externalBus").apply { isAccessible = true }
            .get(rig.bridge) as ExternalBusController
        bus.onConnection(session, true)
        return bus.onFrontendLoaded(session)?.kind
    }
}
