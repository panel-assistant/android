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
    /** Owner presses that close ADB (Network ADB off, Keep ADB off on), counted in the order they were made: at a
     *  settings page press's door, or at the handler of any other (Home Assistant, a replay, a restore, an
     *  import). A settings page ON gives the lease back at its door and is applied by its handler; a closing
     *  press made between the two is the later choice. */
    private val closingPresses = java.util.concurrent.atomic.AtomicLong()
    private val stampedAtDoor = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun closes(key: String, on: Boolean) = (key == "network_adb" && !on) || (key == "adb_keep_off" && on)

    /** A settings page press passes its door: counted now if it closes ADB, and stamped for its handler. */
    fun stampAtDoor(key: String, on: Boolean): Long {
        if (closes(key, on)) closingPresses.incrementAndGet()
        return closingPresses.get().also { stampedAtDoor["$key=$on"] = it }
    }

    /** That door refused the press, so no handler will take its stamp. */
    fun refusedAtDoor(key: String, on: Boolean, stamp: Long) { stampedAtDoor.remove("$key=$on", stamp) }

    /** The stamp a handler's press carries (its door's, or one taken now), and whether it had a door. */
    fun pressFor(key: String, on: Boolean): Pair<Long, Boolean> {
        stampedAtDoor.remove("$key=$on")?.let { return it to true }
        if (closes(key, on)) closingPresses.incrementAndGet()
        return closingPresses.get() to false
    }

    /** The owner pressed something that closes ADB after the press stamped [stamp]. */
    fun closedSince(stamp: Long): Boolean = closingPresses.get() != stamp

    /** The lease owner; absent until a lease runtime exists. */
    @Volatile var owner: Owner? = null
    /** The app leaves ADB to the helper: the lease holds it off, or Panel Assistant's recovery holds it open. */
    fun ownsRuntime(): Boolean = managed || (recoveryHold && panelAssistantHold)

    /** The owner's Network ADB switch opens ADB now: nothing holds it off, or only Keep ADB off does.
     *  Hardened mode keeps it off until the owner switches to Relaxed mode. */
    fun ownerSwitchWorks(): Boolean = !managed || owner?.hardened() != true

    /** Gives the lease back so ADB reopens, straight away; false leaves everything as it was. Only inside an
     *  owner's [turn]; blocks briefly on the helper, so a caller must not hold the transition gate. */
    fun releaseForOwner(): Boolean = !managed || (ownerSwitchWorks() && owner?.release() == true)

    /**
     * Every owner press that changes ADB (Network ADB, Keep ADB off, Relaxed mode) runs as [press] in its turn on
     * the lease's one queue, so it never acts while a helper request it would race is in flight, and it decides
     * on state read from the helper just before it. A press made during a request runs straight after it, in the
     * order presses were made. [waiting] is the answer when the press could not wait for its own result.
     */
    fun <T> turn(waiting: T, press: () -> T): T = owner?.turn(waiting, press) ?: press()

    /** `"network_adb":{…}` for the status document: whether the lease holds ADB off, and whether the owner's
     *  own switch opens it now (0) or not at all while things stand (null, Hardened mode). */
    fun statusJson(): String =
        "{\"managed\":$managed,\"owner_switch_wait_s\":${if (managed && ownerSwitchWorks()) "0" else "null"}}"

    interface Owner {
        /** Hardened mode keeps the lease whatever Keep ADB off says. */
        fun hardened(): Boolean
        /** Ends a held lease now so ADB reopens; true once the helper no longer holds it. Only in a [turn]. */
        fun release(): Boolean
        /** Arms the lease now when the owner wants it held. Never ends a lease. Only in a [turn]. */
        fun reconcile()
        /** Runs the owner's [press] in its turn on the lease's queue (see [ManagedNetworkAdb.turn]). */
        fun <T> turn(waiting: T, press: () -> T): T
    }
}
