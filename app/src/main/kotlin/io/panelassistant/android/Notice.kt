package io.panelassistant.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Where a [Notice] card stands. A screen keeps the prominence it had before it moved onto the Notice:
 * a card over the dashboard stays an overlay, a screen that replaced the dashboard stays full screen.
 */
internal enum class NoticeForm {
    /** Over the dashboard, which keeps rendering underneath: 86% opaque. */
    OVERLAY,

    /** In place of the dashboard, centred on the panel's own background: opaque. */
    FULL_SCREEN,
}

/**
 * The card's colours: INVERTED against what is behind it — light over a dark backdrop, dark over a light
 * one — as Android's own prominent notices are. A card in the backdrop's own tones read as part of the
 * page rather than a notice (seen on hardware with the restart card). The measured contrast is in
 * [PanelTokens.Notice]; recheck it before changing a colour or [NOTICE_OVERLAY_ALPHA].
 */
internal fun noticeColours(backdropDark: Boolean): NoticeColours =
    if (backdropDark) PanelTokens.Notice.LIGHT else PanelTokens.Notice.DARK

/**
 * The card's edge, in the accent of the BACKDROP's tone rather than the card's: bright blue round a
 * light card on a dark backdrop, deep blue round a dark card on a light one, so the edge contrasts with
 * what is behind it (the card's own accent nearly vanished against the dashboard).
 */
internal fun noticeEdge(backdropDark: Boolean): Int = noticeColours(!backdropDark).accent

/**
 * Whether the card takes the night wordmark (light name) rather than the day one (dark name): the card is
 * inverted against its backdrop, so a dark backdrop gets the light card and the day wordmark.
 */
internal fun noticeWordmarkNight(backdropDark: Boolean): Boolean = !backdropDark

/**
 * The published wordmark (tools/brand) in the ink [night] asks for, whatever the panel's own night mode:
 * its day and night artwork differ only in ink, so a card drawn against the system theme would otherwise
 * put pale ink on a pale card.
 */
internal fun brandWordmark(context: Context, night: Boolean): android.graphics.drawable.Drawable? {
    val config = android.content.res.Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
    }
    return context.createConfigurationContext(config).getDrawable(R.drawable.wordmark)
}

/** 86% opaque over the dashboard: the inverted palette already makes the card stand out. */
internal const val NOTICE_OVERLAY_ALPHA = 0xDB

/** 94% opaque for the compact corner and bottom notices, which cover the least. */
internal const val NOTICE_COMPACT_ALPHA = 0xF0

internal fun noticeWithAlpha(colour: Int, alpha: Int): Int = (colour and 0x00FFFFFF) or (alpha shl 24)

/** The one scale rule for this display; see [PanelTokens.scale]. */
internal fun noticeScale(metrics: DisplayMetrics): Float =
    PanelTokens.scale(minOf(metrics.widthPixels, metrics.heightPixels).toFloat(), metrics.density)

/**
 * The scale for text: the display's scale times Android's font size setting, so a reader who asked for
 * larger text gets it on every Notice as they did when these screens used sp (a non-positive setting
 * counts as 1). Layout sizes use [noticeScale] alone; the card grows with its text and scrolls.
 */
internal fun noticeTextScale(scale: Float, fontScale: Float): Float = scale * (if (fontScale > 0f) fontScale else 1f)

/**
 * The one card every native status surface is drawn from.
 *
 * A brand row — the Panel Assistant lock-up with the app version and build beneath it, and one pill word
 * naming the step or the severity — then the slots a screen fills: one hero (a number, a word, or a QR
 * when the owner must act elsewhere), one line, an optional step track, actions, and a muted footer of
 * exact figures. A screen supplies words and numbers; it never draws its own card, palette, sizes or
 * brand row. The version is always there so a maintainer can name the build from the panel itself.
 *
 * Sizes are pixels on the 480 x 480 baseline panel, scaled by [PanelTokens.scale], so the card grows on
 * a small display and stops at the display's density. Touch targets never fall below 48 dp.
 */
internal class Notice(
    private val context: Context,
    private val backdropDark: Boolean,
    private val form: NoticeForm,
    /** False for a transient card that names no build (the voice conversation): the pill heads the card alone. */
    brand: Boolean = true,
    /**
     * The card's opacity. Opaque for a card read from across the room over a busy dashboard: at the overlay's
     * 86% the dashboard's own text showed through the voice card on hardware.
     */
    surfaceAlpha: Int = if (form == NoticeForm.OVERLAY) NOTICE_OVERLAY_ALPHA else 0xFF,
) {
    private val metrics = context.resources.displayMetrics
    val scale: Float = noticeScale(metrics)
    private val textScale = noticeTextScale(scale, context.resources.configuration.fontScale)
    val colours: NoticeColours = noticeColours(backdropDark)

    fun px(base: Float): Int = (base * scale).toInt()

    /** A touch target: the baseline size, never under 48 dp. */
    private fun target(base: Float): Int = maxOf(px(base), PanelTokens.dp(48, metrics.density))

    /** The card's width: the display less its margins, and never wider than a phone-sized card. */
    val widthPx: Int = minOf(metrics.widthPixels - 2 * px(MARGIN), px(MAX_WIDTH)).coerceAtLeast(1)

    /** The width the slots get inside the card's padding. */
    private val innerWidthPx: Int = (widthPx - 2 * px(PAD_H)).coerceAtLeast(1)

    private val pillText = text(PILL, colours.text, PanelFont.medium(context)).apply { maxLines = 1 }
    private val pillDot = dot(DOT)
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(14f), px(7f), px(14f), px(7f))
        background = GradientDrawable().apply {
            cornerRadius = 999f * scale
            setColor(noticeWithAlpha(colours.accent, 0x24))
        }
        addView(pillDot, LinearLayout.LayoutParams(px(DOT), px(DOT)).apply { marginEnd = px(8f) })
        addView(pillText)
        visibility = View.GONE
    }
    // The build, quieter than the muted text, and only on a pre-release (maintainer, 2026-10-08).
    private val caption = text(CAPTION, noticeWithAlpha(colours.muted, CAPTION_ALPHA)).apply {
        maxLines = 1
        fontFeatureSettings = "tnum"
        text = appVersion()
        visibility = if (noticeShowsBuild(BuildConfig.VERSION_NAME)) View.VISIBLE else View.GONE
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** The card. The caller places it: an overlay in the dashboard's frame, a full-screen host centred. */
    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(PAD_H), px(PAD_V), px(PAD_H), px(PAD_V + 2f))
        background = GradientDrawable().apply {
            cornerRadius = CORNER * scale
            setColor(noticeWithAlpha(colours.surface, surfaceAlpha))
            setStroke(maxOf(3, px(BORDER)), noticeEdge(backdropDark))
        }
        if (brand) addView(brandRow(backdropDark))
        else addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * The lock-up (mark cropped to its device frame, the name in Sora at 0.71 of the mark, baseline on
     * the house) from the brand asset set, the version beneath the name, and the pill at the end.
     */
    private fun brandRow(backdropDark: Boolean): View {
        val lockup = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ImageView(context).apply {
                setImageDrawable(brandWordmark(context, noticeWordmarkNight(backdropDark)))
                scaleType = ImageView.ScaleType.FIT_START
                contentDescription = context.getString(R.string.wordmark_description)
            }, LinearLayout.LayoutParams(px(MARK * WORDMARK_ASPECT), px(MARK)))
            addView(caption, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(MARK * WORDMARK_NAME_INSET); topMargin = px(2f) })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(lockup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill)
        }
    }

    /**
     * The pill word: the step, or the severity. Blank hides it. [warn] turns the dot amber, the compact
     * notices' warning colour in the card's own tone, for a step that failed.
     */
    fun setPill(word: CharSequence, warn: Boolean = false) {
        pillText.text = word
        val dot = if (!warn) colours.accent
        else if (backdropDark) PanelTokens.Chip.LIGHT.warningBorder else PanelTokens.Chip.DARK.warningBorder
        (pillDot.background as GradientDrawable).setColor(dot)
        pill.visibility = if (word.isBlank()) View.GONE else View.VISIBLE
    }

    /** Append the panel's running state to the version under the name. */
    fun setCaption(suffix: String) {
        caption.text = if (suffix.isBlank()) appVersion() else "${appVersion()} · $suffix"
    }

    /**
     * Replace the slots. The brand row is untouched, so nothing in it moves or redraws. Rows are spaced
     * by one gap, except that the line under a hero sits close to it: the two read as one statement.
     */
    fun setBody(vararg rows: View?) {
        body.removeAllViews()
        var previous: View? = null
        rows.filterNotNull().forEach { row ->
            val gap = when {
                row.tag == LINE_TAG && previous?.tag == HERO_TAG -> HERO_LINE_GAP
                else -> GAP
            }
            val params = (row.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            body.addView(row, params.apply { topMargin = px(gap) })
            previous = row
        }
    }

    /** One number (a countdown, a count) at the hero size, or a word at the smaller hero size. */
    fun hero(value: CharSequence, word: Boolean = false): TextView = text(HERO_NUMBER, colours.text, PanelFont.light(context)).apply {
        tag = HERO_TAG
        maxLines = if (word) 2 else 1
        fontFeatureSettings = "tnum"
        letterSpacing = -0.01f
        setHero(this, value, word)
    }

    /** Switch a hero between a number and a word without rebuilding it. */
    fun setHero(hero: TextView, value: CharSequence, word: Boolean) {
        hero.text = value
        hero.setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (word) HERO_WORD else HERO_NUMBER) * textScale)
    }

    /**
     * A sentence as the hero (what someone said), smaller than a word hero so a spoken command fits in a
     * line or two, and in the medium face so it still reads across a room.
     */
    fun sentence(value: CharSequence): TextView = text(HERO_SENTENCE, colours.text, PanelFont.medium(context)).apply {
        tag = HERO_TAG
        maxLines = 3
        text = value
    }

    /** The height of a number hero, so a hero that switches between a number and a word never resizes the card. */
    fun heroNumberHeightPx(): Int = Paint().apply { textSize = HERO_NUMBER * textScale; typeface = PanelFont.light(context) }
        .fontMetricsInt.let { it.bottom - it.top }

    /** The one sentence on what is happening. */
    fun line(value: CharSequence): TextView = text(LINE, colours.secondary).apply {
        tag = LINE_TAG
        text = value
        if (form == NoticeForm.OVERLAY) maxLines = 2
    }

    /** Exact figures or a diagnostic detail, muted. [small] is for a raw technical detail under it. */
    fun footer(value: CharSequence, small: Boolean = false): TextView =
        text(if (small) FOOTER_SMALL else FOOTER, colours.muted).apply { text = value }

    /** Any other content a screen leads with (the proximity pictogram), placed as a slot. */
    fun slot(view: View, widthPx: Int, heightPx: Int): View = view.apply {
        layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    /**
     * A QR as the hero, with its prompt beside it, or null when it cannot be encoded — the caller then
     * shows the address on its own and loses a convenience rather than the instruction. Black on white
     * whatever the theme: a QR is read by a camera, and inverting one costs scans on cheap decoders.
     */
    fun qr(url: String, description: CharSequence, prompt: CharSequence): View? {
        val side = px(QR)
        val bitmap = qrBitmap(url, side) ?: return null
        return LinearLayout(context).apply {
            tag = HERO_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                contentDescription = description
                setPadding(px(10f), px(10f), px(10f), px(10f))
                background = GradientDrawable().apply { cornerRadius = 12f * scale; setColor(Color.WHITE) }
            }, LinearLayout.LayoutParams(side + px(20f), side + px(20f)).apply { marginEnd = px(20f) })
            addView(text(QR_PROMPT, colours.text).apply { text = prompt }, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
        }
    }

    /** Work in flight with no measure of how far along it is: a thin moving bar in the accent. */
    fun progress(): View = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(colours.accent)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(SEGMENT))
    }

    /** A step track of two to five [labels]; [NoticeTrack.show] marks the current step and the fills. */
    fun track(labels: List<CharSequence>, max: Int): NoticeTrack {
        val corner = SEGMENT * scale / 2
        val segments = labels.map {
            ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                this.max = max
                progressDrawable = LayerDrawable(arrayOf(
                    GradientDrawable().apply { cornerRadius = corner; setColor(noticeWithAlpha(colours.text, 0x1F)) },
                    ClipDrawable(GradientDrawable().apply { cornerRadius = corner; setColor(colours.accent) },
                        Gravity.START, ClipDrawable.HORIZONTAL),
                )).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
            }
        }
        val names = labels.map { label -> text(LABEL, colours.muted).apply { text = label; maxLines = 1 } }
        fun row(children: List<View>, height: Int) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            children.forEachIndexed { i, child ->
                addView(child, LinearLayout.LayoutParams(0, height, 1f).apply { if (i > 0) marginStart = px(SEGMENT_GAP) })
            }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(segments, px(SEGMENT)))
            addView(row(names, ViewGroup.LayoutParams.WRAP_CONTENT), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = px(10f) })
        }
        return NoticeTrack(view, segments, names, colours)
    }

    /**
     * An action, at least 48 dp tall. One per screen is [primary]: filled in the card's accent. The rest
     * are quiet: outlined in the card's ink. Both show pressed, focused and disabled states, because a
     * screen that disables its actions while it works must not look as though it is idle.
     */
    fun action(label: CharSequence, primary: Boolean = false, onClick: (Button) -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, ACTION * textScale)
        typeface = if (primary) PanelFont.medium(context) else PanelFont.regular(context)
        stateListAnimator = null
        val corner = ACTION_CORNER * scale
        fun face(fill: Int, stroke: Int?) = GradientDrawable().apply {
            cornerRadius = corner
            setColor(fill)
            if (stroke != null) setStroke(maxOf(1, px(1f)), stroke)
        }
        val ink = colours.text
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), face(noticeWithAlpha(ink, 0x14), null))
            if (primary) {
                addState(intArrayOf(android.R.attr.state_pressed), face(noticeWithAlpha(colours.accent, 0xC0), null))
                addState(intArrayOf(android.R.attr.state_focused), face(colours.accent, ink))
                addState(intArrayOf(), face(colours.accent, null))
            } else {
                addState(intArrayOf(android.R.attr.state_pressed), face(noticeWithAlpha(ink, 0x1F), noticeWithAlpha(ink, 0x3D)))
                addState(intArrayOf(android.R.attr.state_focused), face(Color.TRANSPARENT, colours.accent))
                addState(intArrayOf(), face(Color.TRANSPARENT, noticeWithAlpha(ink, 0x3D)))
            }
        }
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colours.muted, if (primary) colours.surface else ink),
        ))
        // After the background, always: a background's own padding replaces padding set before it.
        val side = px(if (primary) 22f else 18f)
        setPadding(side, 0, side, 0)
        minimumHeight = target(ACTION_HEIGHT)
        minHeight = target(ACTION_HEIGHT)
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { onClick(this) }
    }

    /**
     * Actions side by side while they fit the card, stacked at its full width when they do not: natural
     * widths side by side clip on a narrow panel or at a large font scale, and no scroll cures that.
     */
    fun actions(vararg buttons: Button): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { i, button ->
            addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { if (i > 0) marginStart = px(ACTION_GAP) })
        }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        measure(unspecified, unspecified)
        if (measuredWidth > innerWidthPx) {
            orientation = LinearLayout.VERTICAL
            buttons.forEachIndexed { i, button ->
                (button.layoutParams as LinearLayout.LayoutParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    marginStart = 0
                    topMargin = if (i == 0) 0 else px(ACTION_GAP)
                }
            }
        }
    }

    private fun text(size: Float, colour: Int, face: Typeface = PanelFont.regular(context)) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * textScale)
        setTextColor(colour)
        typeface = face
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun dot(size: Float) = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colours.accent) }
        minimumWidth = px(size)
    }

    internal companion object {
        const val HERO_TAG = "noticeHero"
        const val LINE_TAG = "noticeLine"

        /** The lock-up's width over its height, and where its name starts, from the brand asset set. */
        const val WORDMARK_ASPECT = 373.57f / 56f
        const val WORDMARK_NAME_INSET = 84.67f / 56f

        // Baseline sizes: pixels on a 480x480 panel at density 1.0, scaled by [PanelTokens.scale].
        const val MARGIN = 16f
        const val MAX_WIDTH = 600f
        const val PAD_H = 28f
        const val PAD_V = 24f
        const val GAP = 18f
        const val HERO_LINE_GAP = 4f
        const val CORNER = 24f
        const val BORDER = 4f
        /** The lock-up's mark height; the name is 0.71 of it. A quarter larger than the canvas's 24 (maintainer, 2026-10-08). */
        const val MARK = 30f
        /** The build caption's opacity over the muted colour: lighter, still about 4.5:1 on the card. */
        const val CAPTION_ALPHA = 0xD0
        const val CAPTION = 11f
        const val PILL = 16f
        const val DOT = 9f
        const val HERO_NUMBER = 96f
        const val HERO_WORD = 56f
        const val HERO_SENTENCE = 30f
        const val LINE = 20f
        const val SEGMENT = 12f
        const val SEGMENT_GAP = 6f
        const val LABEL = 16f
        const val FOOTER = 16f
        const val FOOTER_SMALL = 13f
        const val ACTION = 17f
        const val ACTION_HEIGHT = 48f
        const val ACTION_CORNER = 14f
        const val ACTION_GAP = 12f
        const val QR = 130f
        const val QR_PROMPT = 26f
    }
}

/** A [Notice] step track: segments filled out of the track's max, the current step's name in bold. */
internal class NoticeTrack(
    val view: View,
    val segments: List<ProgressBar>,
    private val names: List<TextView>,
    private val colours: NoticeColours,
) {
    private var pulse: ValueAnimator? = null
    private var pulsing: Int? = null

    init {
        // A pulse must not outlive the card it is drawn on.
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = pulse(null)
        })
    }

    /**
     * Mark [current] (or none) and, when given, set each segment's fill. [pulsing] fills the current segment
     * and breathes it, inside its own rounded segment, for a step under way with no measure of how far
     * along it is; the caller passes false when Android's animations are off, and the bold name alone marks
     * the step.
     */
    fun show(current: Int?, fills: List<Int>? = null, pulsing: Boolean = false) {
        names.forEachIndexed { i, name ->
            val active = i == current
            name.setTextColor(if (active) colours.text else colours.muted)
            name.typeface = if (active) PanelFont.medium(name.context) else PanelFont.regular(name.context)
        }
        fills?.forEachIndexed { i, fill -> segments.getOrNull(i)?.progress = fill }
        pulse(current?.takeIf { pulsing && it in segments.indices })
    }

    private fun pulse(index: Int?) {
        if (index != pulsing) {
            pulse?.cancel()
            pulse = null
            pulsing?.let { fill(it).alpha = 0xFF }
            pulsing = index
            if (index != null) {
                val segment = segments[index]
                val layer = fill(index)
                pulse = ValueAnimator.ofInt(PULSE_LOW, PULSE_HIGH).apply {
                    duration = PULSE_MS
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        layer.alpha = it.animatedValue as Int
                        segment.invalidate()
                    }
                    start()
                }
            }
        }
        // Full, but never at full strength while it breathes: the fill says "under way", not "this far".
        pulsing?.let { segments[it].progress = segments[it].max }
    }

    private fun fill(index: Int) =
        (segments[index].progressDrawable as LayerDrawable).findDrawableByLayerId(android.R.id.progress)

    private companion object {
        const val PULSE_LOW = 0x26
        const val PULSE_HIGH = 0xB3
        const val PULSE_MS = 900L
    }
}

/**
 * The compact form: a corner chip or a bottom notice the size of the toast it replaces. The mark, a dot
 * and the title on one line, an optional quiet detail, and an optional 48 dp dismiss. It carries no name
 * or version: it is small and transient, and the mark already says who is speaking.
 *
 * Only the dismiss takes a touch; the rest passes taps to the dashboard beneath.
 */
internal class CompactNotice(
    context: Context,
    backdropDark: Boolean,
    /** A stadium with the detail beside the title (the bottom notice), or a card with it beneath (a chip). */
    inline: Boolean,
    onDismiss: (() -> Unit)?,
    dismissDescription: CharSequence? = null,
) {
    private val metrics = context.resources.displayMetrics
    private val scale = noticeScale(metrics)
    private val textScale = noticeTextScale(scale, context.resources.configuration.fontScale)
    private fun px(base: Float): Int = (base * scale).toInt()
    val colours: NoticeColours = noticeColours(backdropDark)

    val title: TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (inline) 15f else 17f) * textScale)
        setTextColor(colours.text)
        typeface = PanelFont.medium(context)
        maxLines = 1
    }
    val detail: TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (if (inline) 15f else 13f) * textScale)
        setTextColor(colours.secondary)
        typeface = PanelFont.regular(context)
        maxLines = if (inline) 2 else 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val mark = ImageView(context).apply {
        setImageResource(R.drawable.ic_pa_mark)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.panel_assistant)
    }
    private val inline = inline

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = (if (inline) 999f else 12f) * scale
            setColor(noticeWithAlpha(colours.surface, NOTICE_COMPACT_ALPHA))
            setStroke(maxOf(2, px(2f)), noticeEdge(backdropDark))
        }
        elevation = 6 * metrics.density
        val end = if (onDismiss != null) 0 else px(if (inline) 16f else 14f)
        setPadding(px(if (inline) 12f else 10f), px(if (inline) 9f else 6f), end, px(if (inline) 9f else 6f))
        addView(mark, LinearLayout.LayoutParams(px(compactMarkHeight(false) * 60f / 45f), px(compactMarkHeight(false) * 60f / 45f)).apply { marginEnd = px(if (inline) 10f else 8f) })
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colours.accent) }
            }, LinearLayout.LayoutParams(px(7f), px(7f)).apply { marginEnd = px(6f) })
            addView(title)
        }
        if (inline) {
            addView(titleRow)
            addView(detail, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(10f) })
        } else {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleRow)
                addView(detail, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = px(1f) })
            })
        }
        if (onDismiss != null) {
            val side = maxOf(px(48f), PanelTokens.dp(48, metrics.density))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_notice_dismiss)
                imageTintList = ColorStateList.valueOf(colours.secondary)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = dismissDescription
                setOnClickListener { onDismiss() }
            }, LinearLayout.LayoutParams(side, side).apply { topMargin = -px(6f); bottomMargin = -px(6f) })
        }
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    /** Show [title] and [detail] (blank hides the detail). */
    fun show(title: CharSequence, detail: CharSequence = "") {
        this.title.text = title
        this.detail.text = detail
        this.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        // A chip with its detail line beneath the title is two lines tall, so its mark spans both
        // (maintainer, 2026-10-08); a one-line notice keeps the small mark.
        // The published mark sits in a square, its device frame three quarters of the square's height.
        val side = px(compactMarkHeight(twoLines = !inline && detail.isNotBlank()) * 60f / 45f)
        (mark.layoutParams as LinearLayout.LayoutParams).apply { height = side; width = side }
        mark.requestLayout()
        view.contentDescription = if (detail.isBlank()) title else "$title. $detail"
        view.visibility = View.VISIBLE
    }

    fun hide() {
        view.visibility = View.GONE
    }
}

/**
 * The dashboard reload as the overlay card, the restart card's form: the dashboard cannot be used while it
 * reloads, so a small notice added nothing (maintainer, 2026-10-08). On the window's decor, not in the
 * dashboard's frame, because the reload swaps that frame and a notice inside it vanished with it (seen on
 * hardware). The caller removes it when the dashboard reconnects; it goes by itself after
 * [RELOAD_NOTICE_MAX_MS] so a reload that never connects cannot leave it up, the reconnecting screen taking
 * over by then.
 */
internal fun showReloadCard(activity: android.app.Activity, previous: View?, reason: String): View {
    val decor = activity.window.decorView as ViewGroup
    previous?.let { decor.removeView(it) }
    val dark = runCatching { Config(activity).dashboardThemeDark }.getOrNull() ?: true
    val notice = Notice(activity, dark, NoticeForm.OVERLAY)
    notice.setPill(activity.getString(R.string.reload_title))
    notice.setBody(notice.line(reason), notice.progress())
    val margin = notice.px(Notice.MARGIN)
    decor.addView(notice.view, android.widget.FrameLayout.LayoutParams(
        notice.widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
    ).apply { setMargins(margin, margin, margin, margin) })
    notice.view.postDelayed({ decor.removeView(notice.view) }, RELOAD_NOTICE_MAX_MS)
    return notice.view
}

/** The longest the reload card stays up when the dashboard never reconnects. */
internal const val RELOAD_NOTICE_MAX_MS = 60_000L

/** The compact notice's mark height in baseline pixels: spanning both lines of a two-line chip. */
internal fun compactMarkHeight(twoLines: Boolean): Float = if (twoLines) 32f else 18f

/** Whether the brand row shows the build: only on a pre-release, the build a panel that chose them runs. */
internal fun noticeShowsBuild(versionName: String): Boolean = '-' in versionName
