package io.panelassistant.android.platform

/** Optional accessibility-service boundary for global navigation and gesture injection. */
interface AccessibilityActions {
    /** Perform Android's global Back action. */
    fun back(): Boolean

    /** Perform Android's global Recents action. */
    fun recents(): Boolean

    /** Inject and complete a tap at device-pixel coordinates. */
    fun tap(x: Int, y: Int): Boolean
}
