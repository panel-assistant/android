package io.panelassistant.android

/**
 * Layout and colour rules for [StatusSurface], the branded frame every ha-paneld-owned full-screen
 * status screen is built from.
 *
 * Sizes here are dp and text sizes are sp; the two coincide only at a font scale of 1. Nothing here
 * predicts whether a phase FITS a panel — an earlier version of this file modelled that, and the
 * model was deleted because it was optimistic while documenting itself as pessimistic: a lower glyph
 * advance predicts MORE characters per line and therefore FEWER lines, so it under-estimated exactly
 * the case it claimed to guard. Fit is now a property of the drawn frame — the band measures itself
 * and the body scrolls — and of physical acceptance, not of a number in here.
 *
 * This file deliberately imports nothing, so the unit gate can assert it directly: [statusSurfaceSpec]
 * decides every dp and sp, and [StatusSurface] holds no size of its own, which is what keeps the two
 * in step.
 *
 * The numbers are not new. They are the ones the startup screen and the standing screen already use
 * on hardware; this file is where they stop being copied per screen.
 */

/** Below this height a panel is treated as small and every size drops to its tight tier. */
internal const val STATUS_COMPACT_HEIGHT_DP = 560

/** Widest the reading column is ever allowed to get, so text does not run edge to edge on a tablet. */
internal const val STATUS_COLUMN_MAX_DP = 512

/** Widest a full-width recovery action is allowed to get. */
internal const val STATUS_ACTION_MAX_DP = 360

/** The touch target every action is at least as tall as. */
internal const val STATUS_ACTION_HEIGHT_DP = 48

/**
 * The corner, padding, label size and separation of an action, all taken from the standing screen.
 *
 * These are not new numbers. `MainActivity.button` has drawn every control on the QR standing screen
 * this way since before this frame existed, on the same panels, and that screen is the one the
 * maintainer points at as correct. The status screens went their own way instead, and every attempt to
 * re-derive the right values here — including a stadium corner, rejected on sight — was an attempt to
 * invent something the project had already settled.
 *
 * Change these only alongside `MainActivity.button`, or the two screens drift apart again.
 */
internal const val STATUS_ACTION_CORNER_DP = 10
internal const val STATUS_ACTION_PADDING_H_DP = 24
internal const val STATUS_ACTION_PADDING_V_DP = 14
internal const val STATUS_ACTION_LABEL_SP = 16f

/** Between two actions side by side; the standing screen spends 4dp on each of their facing edges. */
internal const val STATUS_ACTION_SIDE_GAP_DP = 8

/** Between two stacked actions; the standing screen spends 8dp above and below each one. */
internal const val STATUS_ACTION_STACK_GAP_DP = 16

/**
 * Above the first action of a group and below the last.
 *
 * A different quantity from the gap BETWEEN two actions, and the two were conflated: the controls
 * were separated from the sentence that explains them, and from the countdown under them, by less
 * than they were separated from each other, so the group read as part of the text rather than as the
 * thing to press. Larger than the stack gap for that reason, and affordable — the tallest screen the
 * frame draws still clears a 480x480 panel with room over.
 */
internal const val STATUS_ACTION_GROUP_GAP_DP = 24

/** Horizontal breathing room reserved either side of the column and of a full-width action. */
internal const val STATUS_SIDE_INSET_DP = 48


/**
 * Resolved sizes for one panel. Every value is dp except the `Sp` fields, which are scaled text
 * sizes.
 *
 * Nothing in here depends on what the body currently says, which is what stops a phase change moving
 * the brand row above it.
 */
internal data class StatusSurfaceSpec(
    val compact: Boolean,
    val brandHeightDp: Int,
    val brandTopInsetDp: Int,
    val brandBottomInsetDp: Int,
    val columnWidthDp: Int,
    val sidePaddingDp: Int,
    val bodyTopInsetDp: Int,
    val bodyBottomInsetDp: Int,
    val headingSp: Float,
    val detailSp: Float,
    val captionSp: Float,
    val brandCaptionSp: Float,
    val rowGapDp: Int,
    val actionGapDp: Int,
    val actionGroupGapDp: Int,
    val actionSideGapDp: Int,
    val actionHeightDp: Int,
    val actionWidthDp: Int,
    val actionPaddingHorizontalDp: Int,
    val actionPaddingVerticalDp: Int,
    val actionCornerDp: Int,
    val actionBorderDp: Int,
    val actionLabelSp: Float,
    val progressWidthDp: Int,
    val progressHeightDp: Int,
    /** The side of the square QR a screen may offer. Sized so the code still scans from arm's
     *  length on the smallest panel while leaving the address legible underneath it. */
    val qrSizeDp: Int,
) {
}

/** Resolve the spec for a panel of the given logical size. */
internal fun statusSurfaceSpec(widthDp: Int, heightDp: Int): StatusSurfaceSpec {
    val compact = heightDp < STATUS_COMPACT_HEIGHT_DP
    return StatusSurfaceSpec(
        compact = compact,
        // 52/72 and the insets below are the startup screen's accepted proportions, kept as-is so
        // adopting this frame is not also a redesign of the two screens already running on panels.
        brandHeightDp = if (compact) 52 else 72,
        // The band sits a little below the top edge, and the body is drawn from the top of its region
        // rather than centred in it, so the mark and the information under it read as one group
        // instead of the mark stranded at the top of the panel with the message adrift in the middle.
        // How far down the group can start is bounded by the tallest phase — three actions and the
        // longest explanation on any screen — still fitting the rest of a 480x480 panel. That bound is
        // established on hardware, not by a model in this file.
        brandTopInsetDp = if (compact) 56 else 72,
        brandBottomInsetDp = if (compact) 8 else 14,
        columnWidthDp = (widthDp - STATUS_SIDE_INSET_DP).coerceAtMost(STATUS_COLUMN_MAX_DP).coerceAtLeast(1),
        sidePaddingDp = if (compact) 24 else 32,
        bodyTopInsetDp = if (compact) 12 else 20,
        bodyBottomInsetDp = if (compact) 16 else 28,
        headingSp = if (compact) 17f else 21f,
        detailSp = if (compact) 13f else 15f,
        captionSp = if (compact) 12f else 13f,
        brandCaptionSp = 12f,
        rowGapDp = if (compact) 10 else 14,
        actionGapDp = STATUS_ACTION_STACK_GAP_DP,
        actionGroupGapDp = STATUS_ACTION_GROUP_GAP_DP,
        actionSideGapDp = STATUS_ACTION_SIDE_GAP_DP,
        // Android's default button is 48dp of touch target plus its own vertical padding.
        actionHeightDp = STATUS_ACTION_HEIGHT_DP,
        actionWidthDp = (widthDp - STATUS_SIDE_INSET_DP).coerceAtMost(STATUS_ACTION_MAX_DP).coerceAtLeast(1),
        // Observed on a panel at vc595: the label touches the border on both sides. Setting a
        // background takes the replaced drawable's padding with it and nothing here ever put any back,
        // so until now these controls had no horizontal room at all.
        actionPaddingHorizontalDp = STATUS_ACTION_PADDING_H_DP,
        actionPaddingVerticalDp = STATUS_ACTION_PADDING_V_DP,
        actionCornerDp = STATUS_ACTION_CORNER_DP,
        actionBorderDp = 1,
        // Not tier-derived, because the standing screen is not: it draws the same control on a 480x480
        // panel and on a 1920x1200 one, and that is the control being matched.
        actionLabelSp = STATUS_ACTION_LABEL_SP,
        progressWidthDp = if (compact) 220 else 280,
        progressHeightDp = 5,
        qrSizeDp = if (compact) 132 else 192,
    )
}

/** Colours for one theme. Hex strings, so this file stays free of the Android graphics types. */
internal data class StatusPalette(
    val background: String,
    val body: String,
    val subtle: String,
    val accent: String,
    val track: String,
    val actionBackground: String,
    val actionText: String,
    /** Secondary actions — Retry, Configure, Skip — drawn by the frame rather than the platform. */
    val actionSurface: String,
    /**
     * The edge of a secondary action.
     *
     * A secondary fill sits close to the page by design, so the fill alone gave the control about
     * 1.3–1.5:1 against the background and it barely read as a control at all. The visible boundary is
     * what carries the contrast: this is held to at least 3:1 against the page, the ratio WCAG asks of
     * a user-interface component's boundary.
     */
    val actionBorder: String,
    /** The surface while an action is held or focused, so a press is visible. */
    val actionPressed: String,
    /** The emphasised action while held or focused. */
    val actionPrimaryPressed: String,
    /** The surface of an action that is disabled — the entity-filter recovery disables them while it works. */
    val actionDisabled: String,
    /**
     * The colour a terminal failure's heading is drawn in.
     *
     * Theme-aware rather than one shade: the light red that reads well against a near-black panel is
     * barely legible on white, and the screen it appears on is the one a person is reading in order
     * to fix something.
     */
    val error: String,
)

internal fun statusPalette(dark: Boolean): StatusPalette = if (dark) {
    StatusPalette(
        background = "#111111",
        body = "#c8ccd2",
        subtle = "#8a8f99",
        accent = "#4a9eff",
        track = "#30343a",
        // 3.87:1 against the dark page and 4.88:1 for its label. The previous #2557a7 reached only
        // 2.70:1 against the page — it read as a slab rather than a control, which the arithmetic
        // -average contrast check could not see.
        actionBackground = "#2f6fd0",
        actionText = "#ffffff",
        actionSurface = "#2a2f36",
        actionBorder = "#6b737e",
        actionPressed = "#3b424b",
        actionPrimaryPressed = "#4a86e0",
        actionDisabled = "#1c1f24",
        error = "#ff6b6b",
    )
} else {
    StatusPalette(
        background = "#ffffff",
        body = "#2a2e34",
        subtle = "#5a6068",
        accent = "#1669d6",
        track = "#dce1e7",
        actionBackground = "#2557a7",
        actionText = "#ffffff",
        actionSurface = "#eef1f5",
        actionBorder = "#8a929c",
        actionPressed = "#dde3ea",
        actionPrimaryPressed = "#1b4a8f",
        actionDisabled = "#f4f6f8",
        error = "#b3261e",
    )
}

/**
 * The one dark/light rule for every status screen.
 *
 * An explicit dashboard theme policy wins outright, because these screens exist to stand in for the
 * dashboard: a panel told to show a dark dashboard must not flash a light screen on the way to it.
 * [forcedDark] is null unless the built-in renderer is the resolved dashboard and the policy is
 * Dark or Light, so a panel rendering through a Companion is unaffected by a setting that cannot
 * reach its dashboard. Deciding from the policy rather than waiting for the renderer to observe its
 * effect is what makes the very first frame after a policy change correct rather than one page load
 * late.
 *
 * Below that, the theme observed from Home Assistant wins where it is set, because a wall panel's
 * look is a deliberate choice rather than a system preference. Where it is unset, Android 10 and
 * newer have a real system setting to follow; below that the platform has no reliable night mode, so
 * the panel's own stored preference is the only honest answer.
 *
 * Screens previously disagreed here — some read the system uiMode, some defaulted to dark — so the
 * same panel could show a light startup screen and a dark failure screen minutes apart.
 */
internal fun statusSurfaceDark(
    forcedDark: Boolean?,
    configuredDark: Boolean?,
    systemDark: Boolean,
    storedDark: Boolean,
    sdkInt: Int,
): Boolean = forcedDark ?: configuredDark ?: if (sdkInt >= 29) systemDark else storedDark

/**
 * Whether an already-built frame can be kept for the next phase.
 *
 * Keeping it is what holds the mark still: a rebuilt frame re-inflates the header, so the mark is
 * torn down and redrawn on a screen whose only actual change was a line of text.
 *
 * Two things force a rebuild, and both are easy to forget. A theme change, because the artwork and
 * every colour differ. And a **geometry** change: the dashboard declares `orientation|screenSize` in
 * its `configChanges`, so Android hands it a new size without recreating it, and a frame built for
 * the old size would keep the old band height, column width and type scale on a panel that is no
 * longer that shape.
 */
internal fun statusSurfaceReusable(
    cachedSpec: StatusSurfaceSpec?,
    cachedDark: Boolean?,
    spec: StatusSurfaceSpec,
    dark: Boolean,
): Boolean = cachedSpec != null && cachedDark != null && cachedSpec == spec && cachedDark == dark
