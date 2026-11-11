package io.panelassistant.android.control

/** Effective ownership by the authenticated local channel; saved manual intent remains separate. */
internal object ManagedNetworkAdb {
    /** How long a panel is without its session before its owner's Network ADB switch works again. */
    const val OWNER_SWITCH_AFTER_MS = 5 * 60_000L

    @Volatile var managed = false
    @Volatile var recoveryHold = false
    /** The lease owner's answer to the owner's own Network ADB switch; absent until a lease runtime exists. */
    @Volatile var ownerSwitch: OwnerSwitch? = null
    /** The hold a lease leaves once its owner has taken it back: the owner's, not Panel Assistant's. */
    @Volatile var releasedToOwner = false
    fun ownsRuntime(): Boolean = managed || (recoveryHold && !releasedToOwner)

    /** Milliseconds until the owner may turn Network ADB on although the lease keeps it off: 0 means now;
     *  null means never while things stand (a session is up, Hardened mode, or nothing is managed). */
    fun ownerSwitchWaitMs(): Long? = if (managed) ownerSwitch?.waitMs() else null

    /** Gives the held lease back so ADB reopens; false leaves everything as it was. */
    fun releaseToOwner(): Boolean = managed && ownerSwitch?.release() == true

    /** `"network_adb":{…}` for the status document and the Configure page: what holds ADB off, and when
     *  the owner's own switch works. */
    fun statusJson(): String {
        val wait = ownerSwitchWaitMs()
        return "{\"managed\":$managed,\"owner_switch_wait_s\":${wait?.let { (it + 999) / 1000 } ?: "null"}}"
    }

    interface OwnerSwitch {
        fun waitMs(): Long?
        fun release(): Boolean
    }
}
