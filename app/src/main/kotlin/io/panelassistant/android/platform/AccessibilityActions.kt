package io.panelassistant.android.platform

/** Optional accessibility-service boundary for global navigation, gesture injection and screen capture. */
interface AccessibilityActions {
    /** Whether the service is connected now, so [back] and [recents] can act. */
    fun connected(): Boolean = false

    /** Perform Android's global Back action. */
    fun back(): Boolean

    /** Perform Android's global Recents action. */
    fun recents(): Boolean

    /** Inject and complete a tap at device-pixel coordinates. */
    fun tap(x: Int, y: Int): Boolean

    /** Whether [screenshot] can work now: the service is connected on Android 11 or later and its
     *  connection holds the screenshot capability ([accessibilityScreenshotGranted]). */
    fun canScreenshot(): Boolean = false

    /** Capture the default display as PNG within [timeoutMs], or null. Never call on the main thread. */
    fun screenshot(timeoutMs: Long): ByteArray? = null

    /** Whether Android's screensaver (a dream) is in front now. False when the service cannot tell. */
    fun dreamShowing(): Boolean = false

    /** Start Meta's normal name editor. Acceptance is not vendor save success; [onFinished] reports
     * persisted native readback or a failure. Authentication is always left to Meta and the owner. */
    fun renamePortal(name: String, onFinished: (Boolean, String) -> Unit): Boolean = false

    /** A bounded, explicit rename owns the vendor administration surface. */
    fun portalRenameActive(): Boolean = false

    /** Withdraw pending native UI work when its service owner stops. */
    fun cancelPortalRename() = Unit
}

/**
 * Whether a screensaver is in front: the active window belongs to a package that provides one of the
 * configured screensavers ([screensavers] holds the raw `screensaver_components` and
 * `screensaver_default_component` values, comma-separated flattened component names). A window
 * event's class cannot tell: on Android 11 a dream's names its root view, `android.widget.FrameLayout`.
 */
internal fun dreamInFront(activePackage: String?, screensavers: List<String?>, ownPackage: String): Boolean {
    if (activePackage == null || activePackage == ownPackage) return false
    return screensavers.filterNotNull().flatMap { it.split(',') }
        .map { it.trim().substringBefore('/') }
        .any { it == activePackage }
}

/**
 * End a screensaver in front before something is brought forward: on Android 11 and later a dream
 * runs as an activity that stays above every activity start, even one from root, so the dashboard
 * would start behind it and never be seen (ha-integration #15). Android's DreamService finishes on
 * Back whether or not the dream is interactive. Back is sent only on a positive reading
 * ([dreamInFront]), because a Back on the dashboard would navigate it.
 */
fun AccessibilityActions.endDream(log: (String) -> Unit): Boolean {
    if (!dreamShowing()) return false
    val ended = back()
    log("screensaver in front -> Back sent=$ended")
    return ended
}

/**
 * Whether a connected accessibility service can take a screenshot: Android 11 or later, and the
 * connection's [capabilities] (`AccessibilityServiceInfo.capabilities`, null when there is no
 * connection) hold `CAPABILITY_CAN_TAKE_SCREENSHOT`. Android fixes the capabilities when the
 * connection is made, and a firmware can mask one, so a live service alone does not mean every
 * capture will answer.
 */
internal fun accessibilityScreenshotGranted(sdkInt: Int, capabilities: Int?): Boolean =
    sdkInt >= 30 && capabilities != null && capabilities and CAPABILITY_CAN_TAKE_SCREENSHOT != 0

/** `AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT` (API 30). */
private const val CAPABILITY_CAN_TAKE_SCREENSHOT = 0x80
