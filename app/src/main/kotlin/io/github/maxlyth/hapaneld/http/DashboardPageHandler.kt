package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.camera.CameraState
import io.github.maxlyth.hapaneld.camera.CameraSurface
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.util.Json.str as jsonStr

/** Owns warm, cold and hydration rendering without changing management refresh admission. */
internal class DashboardPageHandler(
    private val config: Config,
    private val appContext: Context,
    private val profile: DeviceProfile,
    private val camera: CameraSurface,
    private val managementObservations: ManagementObservations,
    private val pageHealth: PageHealth,
    private val screenshots: ScreenshotCache,
    private val rows: DashboardRows,
    private val advisories: DashboardAdvisories,
    private val pages: () -> PageShell,
    private val buildToken: () -> String,
    private val renderConfigConcurrencyHash: () -> String,
) {
    // The panel's physical resolution as a CSS aspect-ratio (e.g. "750/1334") so the Screenshot card can
    // reserve the exact box and not reflow when the image arrives. Sane portrait fallback if unavailable.
    private fun screenAspectRatio(): String = try {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels > 0 && dm.heightPixels > 0) "${dm.widthPixels}/${dm.heightPixels}" else "3/4"
    } catch (e: Throwable) { "3/4" }



    /** Hydration payload for the dashboard: ready-to-inject HTML fragments, rendered by the same
     *  functions as the warm server render so the two paths can't drift. Builds the snapshot (this
     *  is where the probe cost actually lands — once per TTL). */
    fun json(strings: AppStrings): String {
        val s = managementObservations.snapCache.get()
        // One health snapshot for this render — the banner, facts card and diagnostics rows below all read
        // the same WebView/renderer verdict rather than each re-probing (which could otherwise disagree).
        val h = pageHealth.healthInputs()
        val cards = listOf(
            "livetbl",
            "behavtbl",
            "disptbl",
            "updtbl",
            "infotbl",
            "nettbl",
            "proftbl",
            "contexttbl",
            "captbl",
        ).joinToString(",") { id -> "\"$id\":${jsonStr(rows.render(id, s, h, strings))}" }
        return """{"banners":${jsonStr(advisories.render(s, h, strings))},"shot":${s.privilege.typedShellControlReady},"shotCached":${jsonStr(screenshots.placeholderUrl() ?: "")},"versionCode":${BuildConfig.VERSION_CODE},"package":${jsonStr(BuildConfig.APPLICATION_ID)},"controls":${jsonStr(rows.controls(s, strings))},"cards":{$cards}}"""
    }

    fun html(strings: AppStrings, embed: EmbedMode? = null): String {
        // Stale-while-revalidate: render the last-known snapshot instantly (placeholders if none yet)
        // and let the page hydrate/refresh from /api/v1/info when the snapshot is missing or old.
        val s = managementObservations.snapCache.peek()
        // One health snapshot shared by every warm branch below (banner + facts + diagnostics), captured
        // lazily so a cold shell (s == null, nothing rendered warm) still probes nothing.
        val h: PageHealth.Inputs by lazy(LazyThreadSafetyMode.NONE) { pageHealth.healthInputs() }
        val hydrate = s == null || managementObservations.snapCache.ageMs() > ManagementObservations.SNAP_TTL_MS
        val profNote = dashboardProfileNote(profile.profileLinks, strings)
        // A cold shell can safely show the app-private last-successful capture before the capability
        // probes finish. It must not request a new capture until hydration confirms a privileged route.
        val cachedShot = screenshots.placeholderUrl()
        val shotCard = dashboardScreenshotCard(
            s == null, s?.privilege?.typedShellControlReady == true,
            cachedShot, ::screenAspectRatio, strings,
        )
        val cameraCard = dashboardCameraCard(camera.presentation().state != CameraState.ABSENT, strings)
        val rightControls = dashboardHeaderControls(config, strings)
        return pages().pageShell(
            active = "dashboard",
            sectionTitle = null,
            bodyAttrs = """data-ver="${Config.VERSION}" data-build="${buildToken()}" data-cfg="${renderConfigConcurrencyHash()}" data-hydrate="${if (hydrate) "1" else "0"}" data-hardened="${if (config.hardenedSecurityEnabled) "1" else "0"}"""",
            rightControls = rightControls,
            embed = embed,
            extraScripts = """<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="info.js"></script>
""",
            body = dashboardBody(
                config, strings, profNote, shotCard, cameraCard,
                banners = s?.let { advisories.render(it, h, strings) } ?: "",
                controls = rows.controls(s, strings),
                rowHtml = { id -> s?.let { rows.render(id, it, h, strings) } },
            ),
            strings = strings,
            translationPrefixes = setOf("shell.", "dashboard.", "runtime."),
        )
    }
}
