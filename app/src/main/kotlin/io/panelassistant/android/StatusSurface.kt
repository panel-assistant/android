package io.panelassistant.android

import io.panelassistant.android.util.DashboardTheme
import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * A [Configuration] whose night mode is the panel's chosen theme rather than Android's.
 *
 * The wordmark's day and night artwork differ only in ink colour, so a panel forced light while the
 * system is dark would otherwise draw pale ink onto white and read as a blank space where the app's
 * name should be.
 */
private fun themedForPanel(activity: Activity, dark: Boolean): Configuration =
    Configuration(activity.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }

/**
 * The ha-paneld mark, as one view, for every screen that has to say who is speaking.
 *
 * Shared rather than duplicated so that replacing the artwork — or its content description — is one
 * edit and cannot leave a screen behind on the old mark.
 */
internal fun statusBrandMark(activity: Activity, dark: Boolean): ImageView = ImageView(activity).apply {
    setImageDrawable(
        activity.createConfigurationContext(themedForPanel(activity, dark)).getDrawable(R.drawable.wordmark),
    )
    adjustViewBounds = true
    scaleType = ImageView.ScaleType.FIT_CENTER
    contentDescription = activity.getString(R.string.wordmark_description)
}

/**
 * The same mark as a `data:` URI, for the two interstitials that must be drawn by the WebView rather
 * than by Android views.
 *
 * Those pages are loaded with a null base URL and therefore cannot reference a bundled asset by
 * path, and reaching out for one over the network is exactly what a screen shown because the network
 * or Home Assistant is unreachable must never do. Encoding the bundled resource at runtime keeps the
 * mark local, keeps one copy of the artwork in the tree, and means an artwork change reaches these
 * pages with no separate step.
 */
internal object StatusBrandAsset {
    private val cache = HashMap<Boolean, String>()

    /**
     * Null when the artwork cannot be rendered, so a caller can fall back to the name in text.
     *
     * Drawn and re-encoded rather than copied out of the resource file byte for byte: that shortcut
     * reads the resource as though it were raw, which is only true while the artwork happens to be a
     * bitmap and would break silently the day it becomes a vector. Encoding costs one small PNG
     * compression, once per theme for the life of the process, on a screen that is already static.
     */
    @Synchronized
    fun dataUri(activity: Activity, dark: Boolean): String? = cache[dark] ?: runCatching {
        val themed = activity.createConfigurationContext(themedForPanel(activity, dark))
        val artwork = themed.getDrawable(R.drawable.wordmark) ?: return null
        val width = artwork.intrinsicWidth.coerceAtLeast(1)
        val height = artwork.intrinsicHeight.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        artwork.setBounds(0, 0, width, height)
        artwork.draw(android.graphics.Canvas(bitmap))
        val encoded = java.io.ByteArrayOutputStream().use { sink ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, sink)
            android.util.Base64.encodeToString(sink.toByteArray(), android.util.Base64.NO_WRAP)
        }
        bitmap.recycle()
        "data:image/png;base64,$encoded"
    }.getOrNull()?.also { cache[dark] = it }
}

/**
 * The brand row for the WebView-drawn interstitials, as markup.
 *
 * Sized as a fraction of the viewport with a ceiling rather than in fixed pixels, because these
 * pages are rendered at the dashboard WebView's configured zoom and a fixed size would be a
 * different physical size on every panel. Falls back to the name in text if the artwork cannot be
 * read, since a screen shown during a failure is the last place that may itself fail.
 */
internal fun statusBrandHtmlHeader(activity: Activity, dark: Boolean, palette: StatusPalette): String {
    val uri = StatusBrandAsset.dataUri(activity, dark)
    return if (uri != null) {
        """<img src="$uri" alt="ha-paneld" style="width:min(58%,260px);height:auto;display:block;
           margin:0 auto 1.4em">"""
    } else {
        """<div style="font-size:1.6em;font-weight:700;color:${palette.body};margin:0 0 1.4em">
           ha-paneld</div>"""
    }
}

/** Pressed, focused and disabled variants of a secondary action's surface. */
internal fun statusActionBackground(
    palette: StatusPalette,
    cornerPx: Int,
    borderPx: Int,
    primary: Boolean = false,
): StateListDrawable {
    val idle = if (primary) palette.actionBackground else palette.actionSurface
    val held = if (primary) palette.actionPrimaryPressed else palette.actionPressed
    val off = if (primary) palette.actionDisabled else palette.actionDisabled
    val edge = if (primary) palette.actionBackground else palette.actionBorder
    fun face(fill: String, stroke: String) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        setStroke(borderPx.coerceAtLeast(1), Color.parseColor(stroke))
        cornerRadius = cornerPx.toFloat()
    }
    return StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), face(off, off))
        addState(intArrayOf(android.R.attr.state_pressed), face(held, edge))
        addState(intArrayOf(android.R.attr.state_focused), face(held, palette.accent))
        addState(intArrayOf(), face(idle, edge))
    }
}

/** Label colours matching those states, so a disabled action reads as disabled. */
internal fun statusActionTextColours(palette: StatusPalette, primary: Boolean = false): ColorStateList =
    ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(
            Color.parseColor(palette.subtle),
            Color.parseColor(if (primary) palette.actionText else palette.body),
        ),
    )

/**
 * The fixed band the mark lives in, as one view, shared by every screen that has a changing body.
 *
 * Extracted rather than rebuilt per screen because the two screens that had their own copy each grew
 * a different defect from it: the standing screen chose its background from the panel's configured
 * theme while resolving the artwork through the system's, so a panel whose two disagreed drew the
 * mark dark on dark; and it kept the mark inside the column its own status line changes, so the mark
 * moved every time the line did. Both are impossible from here — one theme value in, and a height
 * that depends on this band's own content and never on the body below it.
 */
internal class StatusBrandHeader(
    activity: Activity,
    dark: Boolean,
    private val spec: StatusSurfaceSpec,
    palette: StatusPalette,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private val caption = TextView(activity).apply {
        text = "v${BuildConfig.VERSION_NAME}"
        setTextColor(Color.parseColor(palette.subtle))
        textSize = spec.brandCaptionSp
        gravity = Gravity.CENTER
    }

    /** Add this to a vertical parent using [bandParams]; it measures its own content. */
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(spec.sidePaddingDp), dp(spec.brandTopInsetDp), dp(spec.sidePaddingDp), dp(spec.brandBottomInsetDp))
        addView(
            statusBrandMark(activity, dark),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(spec.brandHeightDp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        addView(
            caption,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        )
    }

    /**
     * Layout params for the band inside a vertical parent.
     *
     * The height is the band's own content, not a dp box computed from sp sizes. Pinning it to
     * [StatusSurfaceSpec.headerHeightDp] treated a scaled text size as an unscaled length, so at any
     * font scale above 1 the caption was clipped inside a box that could not grow. Measuring its own
     * content still satisfies the requirement — the band holds no phase, so no phase can move it.
     */
    fun bandParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    /** Append the panel's running state to the caption under the mark. */
    fun setCaption(suffix: String) {
        caption.text = if (suffix.isBlank()) {
            "v${BuildConfig.VERSION_NAME}"
        } else {
            "v${BuildConfig.VERSION_NAME} · $suffix"
        }
    }
}

/**
 * True when this frame is already the installed content view.
 *
 * `setContentView` removes every child of the content parent before adding the new one, so handing
 * it the same root again detaches and reattaches the whole hierarchy — including the header this
 * frame exists to hold still. Keeping the instance is not enough on its own; the install has to be
 * skipped too.
 */
internal fun statusSurfaceAlreadyInstalled(currentRoot: View?, surfaceRoot: View): Boolean =
    currentRoot === surfaceRoot && surfaceRoot.parent != null

/**
 * The branded frame shared by every full-screen surface ha-paneld shows in place of the dashboard.
 *
 * A wall panel has no title bar, no address bar and no window chrome, so a screen that opens with a
 * sentence about Home Assistant reads as Home Assistant's — including when what it is actually
 * reporting is ha-paneld's own decision to stop, wait or hold. Every one of these screens therefore
 * opens by naming who is speaking: the ha-paneld mark, horizontally centred, first.
 *
 * The mark sits in a band whose height comes from [StatusSurfaceSpec.headerHeightDp] and so depends
 * only on the panel, never on the body. Phases below can grow, shrink or be replaced wholesale and
 * the mark cannot move, reflow or blink — which is why a screen should keep one instance and call
 * [setBody] on each phase rather than build a new surface per phase.
 *
 * All geometry comes from [statusSurfaceSpec] and all colour from [statusPalette]; this class holds
 * no size of its own, so the unit gate can assert the layout by asserting the spec.
 */
internal class StatusSurface(
    private val activity: Activity,
    /** Resolved by [statusSurfaceDark] — do not re-derive a theme per screen. */
    val dark: Boolean,
) {
    private val density = activity.resources.displayMetrics.density

    val spec: StatusSurfaceSpec = specFor(activity)
    val palette: StatusPalette = statusPalette(dark)

    private fun dp(value: Int): Int = (value * density).toInt()
    private fun color(hex: String): Int = Color.parseColor(hex)

    private val header = StatusBrandHeader(activity, dark, spec, palette)

    private val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        // Drawn from the top of its region, not centred in it. Centred, a short phase floated to the
        // middle of the panel while the mark stayed at the top, and the two read as unrelated things
        // rather than one statement; the spare space now falls below the group instead of between it.
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(spec.bodyTopInsetDp), 0, dp(spec.bodyBottomInsetDp))
    }

    /**
     * Scrolling is containment, not layout. Every phase is sized to fit outright — see
     * [statusSurfaceFits] — and this exists so an unforeseen string, a large font scale or an
     * unusually small panel degrades to a scroll instead of putting a recovery button off-screen.
     */
    private val bodyScroll = ScrollView(activity).apply {
        isFillViewport = true
        addView(
            body,
            FrameLayout.LayoutParams(
                dp(spec.columnWidthDp), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL,
            ),
        )
    }

    /** The view to hand to `setContentView`. */
    val root: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(color(palette.background))
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // Exact header height, then the body takes whatever is left. The mark's position is
                // therefore a function of the panel alone.
                addView(header.view, header.bandParams())
                addView(
                    bodyScroll,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

    /** Replace the changing region. The header is untouched, so nothing above the body moves. */
    fun setBody(vararg rows: View) {
        // A new phase is different content, not a continuation, so it starts where it begins. Keeping
        // the previous offset dropped a reader into the middle of a replacement message.
        bodyScroll.scrollTo(0, 0)
        body.removeAllViews()
        fun isActionGroup(view: View) = view is Button || view.tag == ACTION_ROW_TAG
        rows.forEachIndexed { index, view ->
            val gap = when {
                index == 0 -> 0
                // Between two actions of the same group: the tighter of the two, because they belong
                // together.
                isActionGroup(view) && isActionGroup(rows[index - 1]) -> spec.actionGapDp
                // Entering or leaving a group: the wider one, which is what puts air above the
                // controls and below them.
                isActionGroup(view) || isActionGroup(rows[index - 1]) -> spec.actionGroupGapDp
                else -> spec.rowGapDp
            }
            val existing = view.layoutParams as? LinearLayout.LayoutParams
            body.addView(
                view,
                (existing ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                )).apply {
                    topMargin = dp(gap)
                    if (gravity == -1) gravity = Gravity.CENTER_HORIZONTAL
                },
            )
        }
    }

    /** Append the panel's running state to the fixed caption under the mark. */
    fun setBrandCaption(suffix: String) = header.setCaption(suffix)

    fun heading(text: CharSequence): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color(palette.body))
        textSize = spec.headingSp
        gravity = Gravity.CENTER
    }

    fun detail(text: CharSequence, accent: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color(if (accent) palette.accent else palette.subtle))
        textSize = spec.detailSp
        gravity = Gravity.CENTER
    }

    fun caption(text: CharSequence, accent: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(color(if (accent) palette.accent else palette.subtle))
        textSize = spec.captionSp
        gravity = Gravity.CENTER
    }

    fun progress(): ProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = PROGRESS_RESOLUTION
        progress = 0
        progressTintList = ColorStateList.valueOf(color(palette.accent))
        progressBackgroundTintList = ColorStateList.valueOf(color(palette.track))
        isIndeterminate = false
        layoutParams = LinearLayout.LayoutParams(
            dp(spec.progressWidthDp), dp(spec.progressHeightDp).coerceAtLeast(3),
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    /**
     * A square QR of [text], or null when it cannot be encoded — in which case the caller shows the
     * address on its own and loses a convenience rather than the instruction.
     *
     * The code is drawn black on white whatever the panel theme is, which looks deliberate beside a dark
     * screen and is: a QR is read by a camera, not by a person, and inverting one costs scans on cheap
     * phone decoders for the sake of matching a palette nothing else here has to match.
     */
    fun qr(text: String, description: CharSequence): ImageView? {
        val bitmap = qrBitmap(text, dp(spec.qrSizeDp)) ?: return null
        return ImageView(activity).apply {
            setImageBitmap(bitmap)
            contentDescription = description
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(spec.qrSizeDp), dp(spec.qrSizeDp))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
    }

    fun action(
        label: CharSequence,
        primary: Boolean = false,
        fullWidth: Boolean = false,
        onClick: (Button) -> Unit,
    ): Button = Button(activity).apply {
        text = label
        // The standing screen's label treatment, which is the one already accepted on these panels.
        // Left to the platform the label arrives upper-cased, because the theme's button style DOES
        // reach this control — observed at vc595, where it reads OPEN PANEL SETTINGS while the same
        // action on the standing screen reads as a sentence.
        isAllCaps = false
        textSize = spec.actionLabelSp
        // EVERY action is coloured from the panel's palette, not only the primary one.
        //
        // A default AppCompat button takes its colours from the Activity theme, which follows the
        // SYSTEM night setting — so on a panel configured light while Android is dark, Retry,
        // Configure, Skip and Disable came out dark-themed on a light screen while the primary button
        // beside them was correct. This is the third defect of the same shape (the standing screen's
        // artwork, its cached palette, and now these), and they share one cause: the frame coloured
        // what it remembered to colour and let the platform theme own the rest.
        if (primary) {
            // The emphasised action needs the same state cues as the quiet one: a tint alone gives no
            // pressed, focused or disabled distinction either.
            background = statusActionBackground(
                palette, dp(spec.actionCornerDp), dp(spec.actionBorderDp), primary = true,
            )
            setTextColor(statusActionTextColours(palette, primary = true))
        } else {
            // Fill plus a visible boundary. The fill alone left the control at about 1.3-1.5:1
            // against the page, which does not read as something to press.
            // State-aware, not one flat drawable. Replacing the platform background with a single
            // GradientDrawable removed every pressed/focused/disabled cue — and the entity-filter
            // recovery deliberately disables these buttons while it works, so a disabled control that
            // looks identical to an enabled one is a screen that lies about what it is doing.
            background = statusActionBackground(palette, dp(spec.actionCornerDp), dp(spec.actionBorderDp))
            setTextColor(statusActionTextColours(palette))
            // fall through: both branches are state-aware
        }
        setOnClickListener { onClick(this) }
        // AFTER the background, always. A background whose drawable reports padding replaces the
        // view's, so padding set first can be silently discarded — which is the mechanism that left
        // this control on the platform's residual inset in the first place, never a chosen value.
        setPadding(
            dp(spec.actionPaddingHorizontalDp),
            dp(spec.actionPaddingVerticalDp),
            dp(spec.actionPaddingHorizontalDp),
            dp(spec.actionPaddingVerticalDp),
        )
        // The height the layout model charges for an action has to be the height the button actually
        // gets, or the model is measuring a screen nobody sees. It is a floor, not a fixed size, so a
        // label that wraps at a large font scale still grows rather than being clipped.
        minimumHeight = dp(spec.actionHeightDp)
        minHeight = dp(spec.actionHeightDp)
        layoutParams = LinearLayout.LayoutParams(
            if (fullWidth) dp(spec.actionWidthDp) else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    /**
     * A pair of actions, side by side only while they demonstrably fit.
     *
     * Natural widths side by side clip horizontally on a narrow panel or at a large font scale, and a
     * vertical scroll cannot cure horizontal clipping. The row measures itself against the reading
     * column and stacks instead, which is always legible.
     */
    fun actionRow(vararg buttons: Button): LinearLayout = LinearLayout(activity).apply {
        // Marks the row as a group of actions so [setBody] spaces it like one. A type check would
        // have been quieter and wrong: any future row built from a LinearLayout would inherit it.
        tag = ACTION_ROW_TAG
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        // Separated first, so the fit decision below sees the gaps. They had never been applied on
        // this branch at all: two actions side by side were added flush and met at their borders.
        buttons.forEachIndexed { index, button ->
            (button.layoutParams as? LinearLayout.LayoutParams)
                ?.marginStart = if (index == 0) 0 else dp(spec.actionSideGapDp)
            addView(button)
        }
        val columnPx = dp(spec.columnWidthDp)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        measure(unspecified, unspecified)
        if (measuredWidth > columnPx) {
            orientation = LinearLayout.VERTICAL
            buttons.forEachIndexed { index, button ->
                (button.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = dp(spec.actionWidthDp)
                    weight = 0f
                    // The horizontal gap has to go, or every stacked action is indented by it.
                    marginStart = 0
                    // The first stacked action already sits a row gap below what precedes it; adding
                    // the action gap on top of that put it further from its own explanation than the
                    // actions are from each other.
                    topMargin = if (index == 0) 0 else dp(spec.actionGapDp)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        } else {
            // Equal shares of one row, which is what the standing screen does with its own pair. A
            // short label beside a long one otherwise renders as a stub, and the two read as different
            // kinds of thing rather than as two choices.
            buttons.forEach { button ->
                (button.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = 0
                    weight = 1f
                }
            }
            // Weights only ever grow a button here, because this branch already established that the
            // natural widths fit. The row itself takes the same footprint a stacked action would, so
            // the two orientations occupy the same width on the panel.
            layoutParams = LinearLayout.LayoutParams(
                measuredWidth.coerceAtLeast(dp(spec.actionWidthDp)).coerceAtMost(columnPx),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
    }

    companion object {
        /** Identifies an [actionRow] to [setBody], which spaces an action group by the action gap. */
        internal const val ACTION_ROW_TAG = "statusActionRow"

        /** Progress bars report per-mille so a learned estimate moves smoothly rather than in steps. */
        const val PROGRESS_RESOLUTION = 1_000

        fun specFor(activity: Activity): StatusSurfaceSpec {
            val dm = activity.resources.displayMetrics
            return statusSurfaceSpec(
                widthDp = (dm.widthPixels / dm.density).toInt(),
                heightDp = (dm.heightPixels / dm.density).toInt(),
            )
        }

        /** The single theme decision, so no screen re-derives one of its own. */
        fun darkFor(activity: Activity, config: Config): Boolean = statusSurfaceDark(
            // Resolved, never the raw package: blank means Auto, which is the built-in renderer too.
            // Effective, so an Ambient panel's native screens follow the room exactly as its dashboard does.
            forcedDark = DashboardTheme.forcedDark(config.dashboardThemeEffective).takeIf {
                RendererResolver.isBuiltinSelection(config.dashboardPackage, activity.packageName)
            },
            configuredDark = config.dashboardThemeDark,
            systemDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
            storedDark = config.darkMode,
            sdkInt = android.os.Build.VERSION.SDK_INT,
        )
    }
}
