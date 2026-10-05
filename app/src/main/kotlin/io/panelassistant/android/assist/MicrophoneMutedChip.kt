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
import androidx.core.view.doOnPreDraw
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
 *
 * A change is announced before it settles, so the owner learns what the small chip means: muting shows
 * the chip large in the middle of the screen, then shrinks it into its corner; unmuting shows
 * "Microphone on" there and fades it. A dashboard that opens already muted just shows the chip.
 */
internal class MicrophoneMutedChip private constructor(
    private val chip: TextView,
    private val mutedText: String,
    private val onText: String,
) {
    private val settle = Runnable {
        chip.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(MOVE_MS).start()
    }
    private val fade = Runnable {
        chip.animate().alpha(0f).setDuration(FADE_MS).withEndAction { rest(muted = false) }.start()
    }

    /** Show [muted]; [announce] when it has just changed rather than being the state found on resume. */
    fun show(muted: Boolean, announce: Boolean) {
        chip.animate().cancel()
        chip.removeCallbacks(settle)
        chip.removeCallbacks(fade)
        if (!announce) {
            rest(muted)
            return
        }
        chip.text = if (muted) mutedText else onText
        chip.contentDescription = chip.text
        chip.alpha = 1f
        chip.visibility = View.VISIBLE
        chip.bringToFront()
        // Centre it once the new text has been measured, then hold before settling or fading.
        chip.doOnPreDraw {
            val parent = chip.parent as? View ?: return@doOnPreDraw
            chip.scaleX = PROMINENT_SCALE
            chip.scaleY = PROMINENT_SCALE
            chip.translationX = parent.width / 2f - (chip.left + chip.width / 2f)
            chip.translationY = parent.height / 2f - (chip.top + chip.height / 2f)
            chip.postDelayed(if (muted) settle else fade, HOLD_MS)
        }
        chip.announceForAccessibility(chip.text)
    }

    private fun rest(muted: Boolean) {
        chip.text = mutedText
        chip.contentDescription = mutedText
        chip.alpha = 1f
        chip.scaleX = 1f
        chip.scaleY = 1f
        chip.translationX = 0f
        chip.translationY = 0f
        chip.visibility = if (muted) View.VISIBLE else View.GONE
    }

    companion object {
        private const val PAD_H_DP = 12
        private const val PAD_V_DP = 8
        private const val MARGIN_DP = 12
        private const val CORNER_DP = 10
        private const val PROMINENT_SCALE = 2.5f
        private const val HOLD_MS = 2_500L
        private const val MOVE_MS = 700L
        private const val FADE_MS = 500L

        /** A hidden chip added to [decor]; [VoiceAttention.muteShown] drives it. */
        fun attach(context: Context, decor: ViewGroup): MicrophoneMutedChip {
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val config = runCatching { Config(context) }.getOrNull()
            val dark = config?.dashboardThemeDark ?: true
            val strings = CatalogueLoader { context.assets.open(it).bufferedReader().use { reader -> reader.readText() } }
                .strings(AppLocale.resolve(
                    explicit = null, persisted = config?.uiLanguage, acceptLanguage = null,
                    deviceLanguageTag = Locale.getDefault().toLanguageTag(), allowPseudo = BuildConfig.DEBUG,
                ))
            val chip = TextView(context).apply {
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_PX, haNetworkChipTextSizePx(minOf(metrics.widthPixels, metrics.heightPixels).toFloat(), density))
                setTextColor(if (dark) Color.WHITE else Color.BLACK)
                setPadding((PAD_H_DP * density).toInt(), (PAD_V_DP * density).toInt(), (PAD_H_DP * density).toInt(), (PAD_V_DP * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = CORNER_DP * density
                    // Opaque, as the network chip is: the dashboard behind it is arbitrary.
                    setColor(if (dark) Color.parseColor("#202124") else Color.parseColor("#F1F3F4"))
                }
                elevation = 6 * density
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
            return MicrophoneMutedChip(chip, strings.get("shell.microphone_muted"), strings.get("shell.microphone_on"))
        }
    }
}
