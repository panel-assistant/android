package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.camera.CameraState
import io.github.maxlyth.hapaneld.camera.CameraSurface
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathRuntime
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

/** Reads each dashboard row when its HTML or hydration surface renders it. */
internal class DashboardRows(
    private val config: Config,
    private val appContext: Context,
    private val profile: DeviceProfile,
    private val system: SystemController,
    private val sensors: SensorReporter,
    private val camera: CameraSurface,
    private val autoBrightnessHttpApi: AutoBrightnessHttpApi,
    private val effectiveBrightness: () -> Int,
    private val volumePercent: () -> Int,
    private val liveCapabilities: (Capabilities) -> Capabilities,
) {
    private val settingRows = DashboardSettingRows(config)

    fun controls(s: ManagementSnapshot?, strings: AppStrings): String = controlsHtml(
        s?.facts, s?.privilege, profile.hasRecents,
        distinctLauncher = {
            system.resolvedLauncher(config.launcherPackage)?.let { it != appContext.packageName } == true
        },
        strings,
    )

    fun render(
        id: String,
        s: ManagementSnapshot,
        h: PageHealth.Inputs,
        strings: AppStrings,
    ): String = when (id) {
        "infotbl" -> factRowsHtml(s.facts, infoKeys(s), h.webView.tooOld, strings, ::displayCell)
        "nettbl" -> factRowsHtml(s.facts, NET_KEYS, h.webView.tooOld, strings, ::displayCell)
        "proftbl" -> factRowsHtml(s.facts, profileFactKeys(profile, s.facts), h.webView.tooOld, strings, ::displayCell)
        "contexttbl" -> contextRowsHtml(CONTEXT_KEYS, h.webView.reportingQuirk, strings) { key -> contextValue(key, s.facts) }
        "captbl" -> capRowsHtml(s.capabilityRows, strings)
        "livetbl" -> liveRowsHtml(strings)
        "behavtbl" -> settingRows.behaviourRowsHtml(s.live, strings, dashboardAutoHints(system, strings), liveCapabilities(s.caps))
        "disptbl" -> settingRows.displayRowsHtml(
            s.live, DisplaySizingObservation(s.densityCur, s.densityBase, s.fontScale), strings,
            capabilities = { liveCapabilities(s.caps) },
            proximity = { sensors.proximitySummary().takeIf { sensors.hasProximity() } },
        )
        "updtbl" -> settingRows.updatesRowsHtml(s.live, strings) { liveCapabilities(s.caps) }
        else -> error("Unknown dashboard table: $id")
    }

    private fun displayCell(v: String): String {
        val observation = DisplayGeometryReport.observe(appContext) ?: return esc(v)
        val size = profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx)?.physical
            ?: return esc(v)
        return displayCell(v, size)
    }

    private val NET_KEYS = listOf("Local IP", "Local IPv6", "HTTP port", "MQTT", "mDNS", "Network ADB")
    private val HA_LIFECYCLE_FACT = "HA lifecycle"
    private val HA_NETWORK_FACT = "HA network path"
    private val HA_RENDERER_FACT = "HA renderer"
    private val CAMERA_FACT = "Camera"

    // Order is the render order of the Runtime diagnostics card. "Wi-Fi stability" leads because it is
    // absent on a healthy panel and only ever appears when the network under everything else on this
    // card has been dropping out — so when it IS shown it explains the rows below it, and reading it
    // last is reading it too late. "HA renderer" follows it for the same reason one place down: it is
    // the panel's headline outcome — whether the dashboard is actually up — and every row below it
    // describes machinery that exists to keep it up. "HA network path" sits between them: it is the
    // measured path to the server every row below depends on, and the likeliest reason a dashboard
    // that IS rendered still feels broken.
    private val CONTEXT_KEYS = listOf(
        "Wi-Fi stability", HA_NETWORK_FACT, HA_RENDERER_FACT, "MQTT state", "State convergence", "Local-state sync",
        "App database", "Security mode", "Audio playback", CAMERA_FACT, "Log shipping", HA_LIFECYCLE_FACT,
    )
    private val BEHAVIOUR_FACT_KEYS = setOf(
        "Keep panel responsive", "Prevent idle dim", "Android dashboard lock", "Navbar",
    )
    private fun infoKeys(s: ManagementSnapshot): List<String> =
        s.facts.keys.filter {
            it !in NET_KEYS && it !in PROFILE_FACT_KEYS && it !in CONTEXT_KEYS && it !in BEHAVIOUR_FACT_KEYS
        }

    // The lifecycle state changes DURING an outage, so this row is rendered from the live
    // snapshot rather than the stale-while-revalidate facts cache AND is then kept current by
    // the same ten-second `/health` poll that drives the banner — one observation feeding every
    // lifecycle surface. A server-rendered advisory banner used to sit alongside it; it was
    // DELETED rather than synchronised, because a one-shot render cannot retract itself and left
    // an outage warning on screen after recovery.
    // The lifecycle row is rendered even when there is nothing to say yet — as an empty cell the
    // poll can fill. Omitting it meant a panel that began watching AFTER the page was rendered
    // (the watch waits for the renderer to settle) had no element to populate, so the row could
    // never appear without a reload: a surface that can only ever go from present to absent.
    // The renderer row is live for the same reason as the lifecycle row and one more: its
    // whole subject is a state that changes while the page is open. Routing it through the
    // facts cache would let a panel that went blank a minute ago keep saying "rendered" for a
    // TTL — precisely the reassuring-but-wrong answer this row exists to stop giving.
    private fun contextValue(key: String, facts: Map<String, String>): String? = when (key) {
        HA_LIFECYCLE_FACT -> HaLifecycleRuntime.statusText() ?: ""
        // Live and always present for the same reasons as the lifecycle row: the verdict
        // changes while the page is open, and the poll fills the cell from the same `/health`
        // observation that drives the banner. One read of the one state owner.
        HA_NETWORK_FACT -> HaNetworkPathRuntime.statusText() ?: ""
        HA_RENDERER_FACT -> rendererAdmission(appContext, config, autoBrightnessHttpApi).statusText()
        // The camera row is live for the same reason, and it is also where a person reads the
        // stream URL off the panel — with the warning that travels beside it, because the place
        // the URL is copied from is the place somebody is about to paste it into a card on this
        // very panel. A panel whose profile declares no camera has nothing to say and no row.
        CAMERA_FACT -> camera.presentation().takeIf { it.state != CameraState.ABSENT }?.summary
        else -> facts[key]
    }


    private fun liveRowsHtml(strings: AppStrings): String {
        val led = config.lastLed
        val brightness = effectiveBrightness().takeIf { it >= 0 } ?: runCatching {
            android.provider.Settings.System.getInt(appContext.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        return liveRowsHtml(led, brightness, volumePercent(), config.lastNavigate, strings)
    }


}
