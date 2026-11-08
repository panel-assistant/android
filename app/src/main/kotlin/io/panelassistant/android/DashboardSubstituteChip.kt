package io.panelassistant.android

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import io.panelassistant.android.dashboard.EntityLearningProtocol
import io.panelassistant.android.dashboard.homeDashboardIsSubstitute
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.formatNamedTemplate

/**
 * "Dashboard not found", a compact Notice at the bottom of the dashboard window while the panel shows the
 * account's default because this Home Assistant does not offer the dashboard the panel is set to (a panel
 * moved to another Home Assistant keeps its `home_dashboard`). It never takes a touch and has no dismiss:
 * creating that dashboard, or choosing another, clears it.
 *
 * The caller owns removal. Every path that swaps or tears down the content view must call [detach].
 */
internal class DashboardSubstituteChip private constructor(
    private val chip: CompactNotice,
    private val title: String,
    private val detail: String,
) {
    /** Show the notice when [resolution] stands in for [configuredPath], hide it otherwise. */
    fun update(configuredPath: String, resolution: EntityLearningProtocol.HomeDashboardResolution?) {
        val shown = resolution?.takeIf { homeDashboardIsSubstitute(configuredPath, it) }?.path
        if (shown == null) {
            chip.hide()
            return
        }
        chip.show(title, formatNamedTemplate(detail, mapOf("configured" to configuredPath.trim(), "shown" to shown)))
    }

    fun detach() {
        (chip.view.parent as? ViewGroup)?.removeView(chip.view)
    }

    companion object {
        /** A hidden chip added to [root], bottom centre, between the corners the network and microphone chips hold. */
        fun attach(context: Context, root: ViewGroup): DashboardSubstituteChip {
            // Direct Boot: settings unreadable means the dark dashboard, and so the light chip.
            val config = runCatching { Config(context) }.getOrNull()
            val strings = CatalogueLoader.assets(context).strings(CatalogueLoader.nativeLocale(config?.uiLanguage))
            val chip = CompactNotice(context, config?.dashboardThemeDark ?: true, inline = false, onDismiss = null)
            chip.detail.maxLines = 2
            val margin = (12 * context.resources.displayMetrics.density).toInt()
            root.addView(
                chip.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { setMargins(margin, margin, margin, margin) },
            )
            return DashboardSubstituteChip(
                chip,
                strings.get("shell.dashboard_substitute.title"),
                strings.get("shell.dashboard_substitute.detail"),
            )
        }
    }
}
