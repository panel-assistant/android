package io.panelassistant.android.assist

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.haNetworkChipTextSizePx
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import java.util.Locale

/**
 * "Microphone muted", in the bottom-start corner of the dashboard window while Android reports the
 * microphone muted. Sized like the network chip, which holds the opposite corner, and like it never
 * takes a touch. It has no close button: the mute is the owner's own setting and unmuting clears it.
 */
internal object MicrophoneMutedChip {
    private const val PAD_H_DP = 12
    private const val PAD_V_DP = 8
    private const val MARGIN_DP = 12
    private const val CORNER_DP = 10

    /** A hidden chip added to [decor]; [VoiceAttention.muteShown] toggles it. */
    fun attach(context: Context, decor: ViewGroup): TextView {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val config = runCatching { Config(context) }.getOrNull()
        val dark = config?.dashboardThemeDark ?: true
        val text = CatalogueLoader { context.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
            .strings(AppLocale.resolve(
                explicit = null, persisted = config?.uiLanguage, acceptLanguage = null,
                deviceLanguageTag = Locale.getDefault().toLanguageTag(), allowPseudo = BuildConfig.DEBUG,
            ))
            .get("shell.microphone_muted")
        val chip = TextView(context).apply {
            this.text = text
            contentDescription = text
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_PX, haNetworkChipTextSizePx(minOf(metrics.widthPixels, metrics.heightPixels).toFloat(), density))
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            setPadding((PAD_H_DP * density).toInt(), (PAD_V_DP * density).toInt(), (PAD_H_DP * density).toInt(), (PAD_V_DP * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * density
                // Opaque, as the network chip is: the dashboard behind it is arbitrary.
                setColor(if (dark) Color.parseColor("#202124") else Color.parseColor("#F1F3F4"))
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
        }
        val margin = (MARGIN_DP * density).toInt()
        decor.addView(
            chip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ).apply { setMargins(margin, margin, margin, margin) },
        )
        return chip
    }
}
