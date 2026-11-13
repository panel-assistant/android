package io.panelassistant.android.http

import android.content.Context
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.camera.CameraState
import io.panelassistant.android.camera.CameraSurface
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.util.Json.str as jsonStr

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
    /** Whether a capture route serves now, given whether a root route does. */
    private val screenshotReady: (rootReady: Boolean) -> Boolean,
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
            "infotbl",
            "nettbl",
            "proftbl",
            "contexttbl",
            "captbl",
        ).joinToString(",") { id -> "\"$id\":${jsonStr(rows.render(id, s, h, strings))}" }
        return """{"banners":${jsonStr(advisories.render(s, h, strings))},"shot":${screenshotReady(s.privilege.rootControlReady)},"shotCached":${jsonStr(screenshots.placeholderUrl() ?: "")},"versionCode":${BuildConfig.VERSION_CODE},"package":${jsonStr(BuildConfig.APPLICATION_ID)},"controls":${jsonStr(rows.controls(s, strings))},"cards":{$cards}}"""
    }

    fun html(strings: AppStrings, embed: EmbedMode? = null, peer: String? = null): String {
        // Stale-while-revalidate: render the last-known snapshot instantly (placeholders if none yet)
        // and let the page hydrate/refresh from /api/v1/info when the snapshot is missing or old.
        // The sidebar's embed link is the only credential its page loads carry, so a page rendered for
        // it is always the cold shell: the panel's facts, banners and controls arrive by hydration over
        // the administrator's own Home Assistant connection, never in the document a copied link fetches.
        val s = if (embed != null) null else managementObservations.snapCache.peek()
        // One health snapshot shared by every warm branch below (banner + facts + diagnostics), captured
        // lazily so a cold shell (s == null, nothing rendered warm) still probes nothing.
        val h: PageHealth.Inputs by lazy(LazyThreadSafetyMode.NONE) { pageHealth.healthInputs() }
        val hydrate = s == null || managementObservations.snapCache.ageMs() > ManagementObservations.SNAP_TTL_MS
        val profNote = dashboardProfileNote(profile.profileLinks, strings)
        // A cold shell can safely show the app-private last-successful capture before the capability
        // probes finish. It must not request a new capture until hydration confirms a capture route.
        // In the sidebar only the transport reaches the panel, so the placeholder arrives through it too.
        val shotCard = dashboardScreenshotCard(
            s == null, s != null && screenshotReady(s.privilege.rootControlReady),
            screenshots.placeholderUrl(), ::screenAspectRatio, strings, relayed = embed != null,
        )
        val cameraCard = dashboardCameraCard(camera.presentation().state != CameraState.ABSENT, strings)
        val rightControls = dashboardHeaderControls(config, strings)
        return pages().pageShell(
            active = "dashboard",
            sectionTitle = null,
            bodyAttrs = """data-ver="${Config.VERSION}" data-build="${buildToken()}" data-hydrate="${if (s == null) "cold" else if (hydrate) "1" else "0"}" data-hardened="${if (config.hardenedSecurityEnabled) "1" else "0"}"""",
            rightControls = rightControls,
            embed = embed,
            peer = peer,
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
