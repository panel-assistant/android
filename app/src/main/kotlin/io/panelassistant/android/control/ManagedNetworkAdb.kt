package io.panelassistant.android.control

/**
 * Whether the helper's lease holds ADB off on this panel. The lease runs only because the owner chose it:
 * the Keep ADB off setting, or Hardened mode. Panel Assistant never closes ADB on its own; its work goes
 * over its own connection. Saved manual intent (the Network ADB switch) remains separate.
 */
internal object ManagedNetworkAdb {
    /** The helper reports the lease holding ADB off. */
    @Volatile var managed = false
    /** The helper reopened ADB for a recovery or a removal and refuses to close it again in that session. */
    @Volatile var recoveryHold = false
    /** That hold is one Panel Assistant asked for in its current session (a removal or a repair), not one an
     *  owner's own Network ADB press or a Keep ADB off change left behind. Ends with that session. */
    @Volatile var panelAssistantHold = false
    /** The lease owner; absent until a lease runtime exists. */
    @Volatile var owner: Owner? = null
    /** The app leaves ADB to the helper: the lease holds it off, or Panel Assistant's recovery holds it open. */
    fun ownsRuntime(): Boolean = managed || (recoveryHold && panelAssistantHold)

    /** The owner's Network ADB switch opens ADB now: nothing holds it off, or only Keep ADB off does.
     *  Hardened mode keeps it off until the owner switches to Relaxed mode. */
    fun ownerSwitchWorks(): Boolean = !managed || owner?.hardened() != true

    /** Gives the lease back so ADB reopens, straight away; false leaves everything as it was. Blocks briefly on
     *  the helper, so a caller must not hold the transition gate. */
    fun releaseForOwner(): Boolean = !managed || (ownerSwitchWorks() && owner?.release() == true)

    /** `"network_adb":{…}` for the status document: whether the lease holds ADB off, and whether the owner's
     *  own switch opens it now (0) or not at all while things stand (null, Hardened mode). */
    fun statusJson(): String =
        "{\"managed\":$managed,\"owner_switch_wait_s\":${if (managed && ownerSwitchWorks()) "0" else "null"}}"

    interface Owner {
        /** Hardened mode keeps the lease whatever Keep ADB off says. */
        fun hardened(): Boolean
        /** Ends a held lease now so ADB reopens; true once the helper no longer holds it. */
        fun release(): Boolean
        /** Arms the lease now when the owner wants it held. Never ends a lease. */
        fun reconcile()
    }
}
