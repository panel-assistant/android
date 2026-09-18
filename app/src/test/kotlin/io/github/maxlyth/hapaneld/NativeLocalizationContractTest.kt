package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLocalizationContractTest {
    private val productionRoot = File("src/main")
    private val productionKotlin = File(productionRoot, "kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(productionRoot).invariantSeparatorsPath to it.readText() }

    private fun kotlin(path: String): String = productionKotlin.getValue("kotlin/io/github/maxlyth/hapaneld/$path")

    private fun baseStrings(): Map<String, Boolean> {
        return stringsIn(File("src/main/res/values/strings.xml"))
    }

    private fun stringsIn(file: File): Map<String, Boolean> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).associate { index ->
            val attributes = strings.item(index).attributes
            attributes.getNamedItem("name").nodeValue to
                (attributes.getNamedItem("translatable")?.nodeValue != "false")
        }
    }

    @Test fun baseCatalogueHasAUniqueBoundedKeyset() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val catalogue = baseStrings()
        assertEquals(276, document.getElementsByTagName("string").length)
        assertEquals(276, catalogue.size)
        assertEquals(273, catalogue.count { it.value })
        assertEquals(
            setOf("app_name", "home_assistant", "wordmark_description"),
            catalogue.filterValues { !it }.keys,
        )
    }

    @Test fun componentLabelsAndAccessibilityServiceUseResources() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        listOf(
            "@string/config_activity_label",
            "@string/guard_db_activity_label",
            "@string/dashboard_activity_label",
            "@string/admin_launcher_activity_label",
        ).forEach { assertTrue("missing manifest resource $it", manifest.contains(it)) }
        val accessibility = File("src/main/res/xml/accessibility_config.xml").readText()
        assertTrue(accessibility.contains("android:description=\"@string/a11y_description\""))
    }

    @Test fun nativeUiSinksDoNotEmbedEnglishLiterals() {
        val sink = Regex(
            "(?:\\b(?:text|contentDescription|navigationContentDescription|subtitle)\\s*=|" +
                "\\.(?:setText|setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton|" +
                "setContentTitle|setContentText)\\s*\\(|" +
                "\\b(?:menu\\.add|surface\\.(?:heading|detail|caption|action)|text|button|Tile|" +
                "updateForegroundStatus|startForegroundCompat|announceDeliberateRestart|" +
                "showBlockedAdmissionScreen|foregroundNotification)\\s*\\()\\s*\"([^\"\\n]*)\"",
        )
        val deliberateNonLanguageLiterals = setOf(
            "ha-paneld",
            "v\${BuildConfig.VERSION_NAME}",
            "\${(brightness.getCommanded().coerceAtLeast(0) * 100 + 127) / 255}%",
            "\${volume.getPercent()}%",
        )
        productionKotlin.forEach { (name, source) ->
            val violations = sink.findAll(source)
                .map { it.groupValues[1] }
                .filter { literal -> literal.any(Char::isLetter) && literal !in deliberateNonLanguageLiterals }
                .toList()
            assertEquals("hardcoded native UI sink in $name", emptyList<String>(), violations)
        }
    }

    @Test fun everyAppStringReferenceResolvesAndEveryFrozenKeyIsAccountedFor() {
        val catalogue = baseStrings()
        val supplemental = stringsIn(File("src/main/res/values/proximity_wizard.xml"))
        val kotlinReferences = productionKotlin.values.flatMap { source ->
            Regex("(?<!android\\.)R\\.string\\.([A-Za-z0-9_]+)").findAll(source).map { it.groupValues[1] }.toList()
        }.toSet()
        val xmlReferences = productionRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "xml" || it.name == "AndroidManifest.xml") }
            .flatMap { file ->
                Regex("@string/([A-Za-z0-9_]+)").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val references = kotlinReferences + xmlReferences

        assertEquals("missing base resources", emptySet<String>(), references - catalogue.keys - supplemental.keys)
        assertEquals(
            "unaccounted frozen resources",
            emptySet<String>(),
            catalogue.keys - references,
        )
    }

    @Test fun proximityWizardResourcesAreCompleteInEveryReleaseLocale() {
        val base = stringsIn(File("src/main/res/values/proximity_wizard.xml"))
        for (directory in listOf("values-de", "values-es", "values-fr", "values-it", "values-zh-rCN")) {
            val translated = stringsIn(File("src/main/res/$directory/proximity_wizard.xml"))
            assertEquals("$directory proximity strings", base.keys, translated.keys)
            assertTrue("$directory must contain only translated strings", translated.values.all { it })
        }
    }

    @Test fun nativeLocaleFollowsThePersistedUiLanguage() {
        val application = TestSources.kotlin("HaPaneldApp.kt").readText()
        val locale = TestSources.kotlin("NativeLocale.kt").readText()
        assertTrue(application.indexOf("NativeLocale.applyBeforeDatabase(this)") < application.indexOf("reconcileBeforeServices(this)"))
        assertTrue(locale.contains("getSharedPreferences(LEGACY_CONFIG_PREFERENCES, Context.MODE_PRIVATE)"))
        val beforeDatabase = locale.substringAfter("fun applyBeforeDatabase").substringBefore("fun apply(raw: String)")
        assertTrue(!beforeDatabase.contains("Config(") && !beforeDatabase.contains("AppState.preferences"))
        // The database-backed correction moved out of Application.onCreate and into PaneldService,
        // after the promote: constructing Config here opened ha-paneld.db inside the
        // startForegroundService deadline and killed the NSPanel 86 panels. The database is still
        // authoritative, so the correction must exist — just not on the deadline.
        assertTrue(
            "Application.onCreate must not construct Config: it runs inside the foreground-start deadline",
            !application.substringAfter("override fun onCreate()").contains("Config(this)"),
        )
        assertTrue(
            "the authoritative locale is still applied, from PaneldService after it has promoted",
            kotlin("PaneldService.kt").contains("NativeLocale.apply(config.uiLanguage)"),
        )
        assertTrue(locale.contains("AppCompatDelegate.setApplicationLocales(desired)"))
        assertTrue(locale.contains("AppLocale.automaticLocaleOverride(systemLanguageTag(), AppLocale.RELEASE_LOCALES)"))
        assertTrue(locale.contains("ConfigurationCompat.getLocales(Resources.getSystem().configuration)"))
        assertTrue(kotlin("ConfigActivity.kt").contains("NativeLocale.apply(Config(this@ConfigActivity).uiLanguage)"))
        assertTrue(kotlin("DashboardActivity.kt").contains("NativeLocale.apply(Config(this).uiLanguage)"))
        assertTrue(kotlin("AdminLauncherActivity.kt").contains("NativeLocale.apply(Config(this).uiLanguage)"))
        assertTrue(kotlin("MainActivity.kt").contains("NativeLocale.apply(config.uiLanguage)"))
    }

    @Test fun everyNavbarControlHasALocalizedDescription() {
        val navbar = kotlin("control/NavbarController.kt")
        assertEquals(5, Regex("navButton\\(R\\.drawable\\.[^,]+, R\\.string\\.nav_").findAll(navbar).count())
        assertEquals(4, Regex("repeatButton\\(R\\.drawable\\.[^,]+, R\\.string\\.nav_").findAll(navbar).count())
        assertEquals(2, Regex("sliderButton\\(R\\.drawable\\.[^,]+, R\\.string\\.nav_").findAll(navbar).count())
        assertTrue(navbar.contains("contentDescription = context.nativeString(description)"))
        assertTrue(!navbar.contains("contentDescription = context.getString(description)"))
    }

    @Test fun everyNotificationSurfaceResolvesUserCopyFromResources() {
        val service = kotlin("PaneldService.kt")
        val camera = kotlin("camera/CameraForegroundService.kt")
        val recovery = kotlin("GuardDbMaintenanceService.kt")
        assertTrue(service.contains("localizedStorageHealthNotification(snapshot)"))
        assertTrue(service.contains("nativeString(R.string.panel_agent_channel_description)"))
        assertTrue(camera.contains("nativeString(R.string.camera_in_use)"))
        assertTrue(recovery.contains("nativeString(R.string.database_recovery_notification)"))
        listOf(service, camera, recovery).forEach { source ->
            assertEquals(emptyList<String>(), Regex("getString\\(R\\.string").findAll(source).map { it.value }.toList())
        }
        assertTrue(!service.contains("getNotificationChannel(STORAGE_HEALTH_CHANNEL_ID) == null"))
        assertTrue(!service.contains("getNotificationChannel(channelId) == null"))
        assertTrue(!recovery.contains("getNotificationChannel(CHANNEL) == null"))
    }

    @Test fun serviceResourcesUseAnExplicitLocaleBeforeAndroid13() {
        val locale = TestSources.kotlin("NativeLocale.kt").readText()
        val resolver = locale.substringAfter("fun string(context: Context").substringBefore("private const val")
        assertTrue(resolver.contains("Build.VERSION.SDK_INT >= 33"))
        assertTrue(resolver.contains("Configuration(context.resources.configuration)"))
        assertTrue(resolver.contains("setLocale(Locale.forLanguageTag(tag))"))
        assertTrue(resolver.contains("context.createConfigurationContext(configuration)"))
        val server = kotlin("http/PaneldServer.kt")
        val commit = server.substringAfter("config.applyBatch(").substringBefore("val prevDash")
        assertTrue(commit.contains("p[\"ui_language\"]?.let(NativeLocale::apply)"))
        val service = kotlin("PaneldService.kt")
        assertTrue(service.contains("foregroundNotification(channelId, silent = true, nativeString(R.string.starting))"))
    }

    @Test fun everyFiniteDashboardRestartReasonHasANativeLocalization() {
        val finiteReasons = productionKotlin.values.flatMap { source ->
            Regex("system\\.reloadDashboard\\([\\s\\S]{0,300}?reason\\s*=\\s*\"([^\"]+)\"")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
        }.toSet()
        assertEquals(
            setOf(
                "applying the entity filter",
                "applying your settings",
                "clearing the dashboard’s stored data",
                "updating the entity filter",
            ),
            finiteReasons,
        )
        val dashboard = kotlin("DashboardActivity.kt")
        finiteReasons.forEach { reason ->
            assertTrue("restart reason is not localized: $reason", dashboard.contains("\"$reason\""))
        }
    }

    @Test fun entityAttentionUsesTheLocalCopyUntilARealAddressExists() {
        val dashboard = kotlin("DashboardActivity.kt")
        val address = dashboard.substringAfter("private fun entitiesPageAddress()").substringBefore("private fun localizedBootstrapMilestone")
        assertTrue(address.contains("String?"))
        assertTrue(address.contains("return ip?.let { \"http://\$it:8888/entities\" }"))
        assertTrue(!address.contains("port 8888"))
        val presentation = dashboard.substringAfter("val bootstrapHint = surface.detail(").substringBefore("else if (bootstrapProblem")
        assertTrue(presentation.contains("entitiesPageAddress()?.let"))
        assertTrue(presentation.contains("R.string.entity_filter_attention_remote"))
        assertTrue(presentation.contains("R.string.entity_filter_attention_detail"))
        val authLatch = dashboard.substringAfter("private fun renderAuthLatchPage()").substringBefore("private fun installStatusSurface")
        assertTrue(authLatch.contains("?: getString(R.string.config_activity_label)"))
        assertTrue(!dashboard.contains("port 8888 of this panel's IP address"))
    }
}
