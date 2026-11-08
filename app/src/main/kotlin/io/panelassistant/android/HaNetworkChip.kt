package io.panelassistant.android

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.StringRes
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.sensors.HaNetworkPath
import io.panelassistant.android.sensors.HaNetworkPathSeverity
import io.panelassistant.android.sensors.PathProbeCause
import java.util.Locale

/** One presentation-only decision shared across WebView rebuilds in this process. */
internal class HaNetworkChipVisibility {
    private var dismissed = false

    fun dismiss() { dismissed = true }

    fun update(degraded: Boolean, enabled: Boolean): Boolean {
        if (!degraded) dismissed = false
        return degraded && enabled && !dismissed
    }
}

/** What the chip says: a title naming the fault, and figures from the instrument that raised it. */
internal data class HaNetworkChipText(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val args: List<Any>,
)

/**
 * Decide the chip's words from one snapshot. Echo verdicts use the echo history's figures and burst
 * scope; the socket fallback uses its five-minute ping window. Severity is in the words.
 */
internal fun haNetworkChipText(snap: HaNetworkPath.Snapshot, locale: Locale = Locale.getDefault()): HaNetworkChipText {
    val severe = snap.severity == HaNetworkPathSeverity.SEVERE
    val minutes = (snap.windowMs / 60_000L).toInt()
    val echo = snap.echo
    val p95Ms = echo?.p95Ms ?: snap.p95Ms
    if (snap.cause == PathProbeCause.LATENCY && p95Ms >= 0L) {
        val time = if (p95Ms >= 1_000L) String.format(locale, "%.1f s", p95Ms / 1_000.0) else "$p95Ms ms"
        return HaNetworkChipText(
            if (severe) R.string.network_chip_very_high_latency else R.string.network_chip_high_latency,
            if (echo != null) R.string.network_chip_echo_time else R.string.network_chip_ping_time,
            listOf(time),
        )
    }
    val title = if (severe) R.string.network_chip_failing else R.string.network_chip_dropping
    return if (echo != null) {
        HaNetworkChipText(title, R.string.network_chip_echoes_lost, listOf(echo.sent - echo.received, echo.sent, echo.bursts))
    } else if (snap.probes == 0) {
        HaNetworkChipText(title, R.string.network_chip_no_ping_answered, listOf(minutes))
    } else {
        HaNetworkChipText(title, R.string.network_chip_pings_lost, listOf(snap.networkFailures, snap.probes, minutes))
    }
}

/**
 * The native "Home Assistant network" chip: a compact [Notice] in the bottom-end corner of the renderer's
 * root frame, so the dashboard keeps rendering underneath and nothing is destroyed. It is deliberately not
 * the restart card: that explains an outage and may stand over the page for as long as one lasts; this
 * states a degraded path that can persist for hours on a working dashboard, so it covers as little as
 * possible and can be dismissed. Only its dismiss takes a touch.
 *
 * The caller owns removal. Every path that swaps or tears down the content view must call [detach].
 */
internal class HaNetworkChip private constructor(private val chip: CompactNotice) {
    /** Render one atomic snapshot; hidden unless the panel holds a socket AND the path is degraded. */
    fun update(snap: HaNetworkPath.Snapshot?, enabled: Boolean) {
        if (!incidentVisibility.update(snap?.degraded == true, enabled)) {
            chip.hide()
            return
        }
        val context = chip.view.context
        val text = haNetworkChipText(requireNotNull(snap))
        chip.show(context.getString(text.title), context.getString(text.detail, *text.args.toTypedArray()))
    }

    fun detach() {
        (chip.view.parent as? ViewGroup)?.removeView(chip.view)
    }

    companion object {
        private val incidentVisibility = HaNetworkChipVisibility()

        /**
         * Attach a hidden chip to [root] in the bottom-end corner, clear of the restart card at the top.
         * Both may show at once during a restart on a bad path.
         */
        fun attach(context: Context, root: ViewGroup): HaNetworkChip {
            // Direct Boot: settings unreadable means the dark dashboard, and so the light chip.
            val config = runCatching { Config(context) }.getOrNull()
            val dashboardDark = config?.dashboardThemeDark ?: true
            val strings = CatalogueLoader.assets(context).strings(CatalogueLoader.nativeLocale(config?.uiLanguage))
            lateinit var chip: CompactNotice
            chip = CompactNotice(
                context, dashboardDark, inline = false,
                onDismiss = { incidentVisibility.dismiss(); chip.hide() },
                dismissDescription = strings.get("shell.ha_network.dismiss"),
            )
            val margin = (12 * context.resources.displayMetrics.density).toInt()
            root.addView(
                chip.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            return HaNetworkChip(chip)
        }
    }
}
