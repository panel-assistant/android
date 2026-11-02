package io.panelassistant.android

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun theDashboardsCreationReadsItsStartupSettingsThroughTheMigration() {
        // Source-text reason: a lifecycle wiring. onCreate cannot run on the plain JVM (androidx's
        // ComponentActivity.onCreate needs the saved-state controller a constructor-less activity lacks, and
        // the unit tests have no Robolectric), so this pins that creation reads the settings the first page
        // opens with through prepareStartupConfig, before the renderer starts; the tests above pin what it does.
        val activity = TestSources.kotlin("DashboardActivity.kt").readText()
        val onCreate = slice(activity, "override fun onCreate(savedInstanceState: Bundle?) {", "\n    }\n")
        val read = "val config = readActivityStateOffMain { prepareStartupConfig(Config(this@DashboardActivity)) }"
        assertTrue("onCreate must read its startup settings through prepareStartupConfig", onCreate.contains(read))
        assertTrue("before the renderer starts", onCreate.indexOf(read) < onCreate.indexOf("initializeRenderer(config)"))
        val prepare = slice(activity, "private fun prepareStartupConfig(config: Config): Config {", "\n    }\n")
        assertTrue("prepareStartupConfig must migrate the store", prepare.contains("config.migrateLiveStore()"))
    }

    /** The text between [start] and [end]; fails rather than answering with the whole file when either is missing. */
    private fun slice(text: String, start: String, end: String): String {
        assertTrue("missing: $start", text.contains(start))
        val after = text.substringAfter(start)
        assertTrue("missing the end of: $start", after.contains(end))
        return after.substringBefore(end)
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
