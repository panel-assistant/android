package io.panelassistant.android.camera

/**
 * Which effects an ended session's finish may still perform.
 *
 * A finish is posted, not synchronous, so it can run after the next session has already been admitted
 * and opened: a lease released under the lock ends one session, and a concurrent acquire can begin the
 * next before the finish for the old one is even enqueued. What the finish does then has to be split in
 * two, because the two halves have different owners:
 *
 * - **The attempt's own hardware** is always its to release: the capture device, its session and
 *   reader, its foreground standing, and its encoder — the codec, the codec's pacer, the parameter
 *   sets it published and the advertisement it made through the transport. Every one of these is named
 *   by attempt identity, so releasing them can never reach a newer attempt's, and a stale finish that
 *   left them alone would leak a codec and strand a retained advertisement.
 * - **The stream readiness, the camera-in-use light and the stream clients** are the session's. Once a
 *   newer session is live they belong to it: replacing its readiness would strand its joiners, dropping
 *   its clients would make them pay the open cost again, and — the one that matters most — putting
 *   the in-use light out while its camera is open would break the privacy contract the light exists to
 *   keep.
 *
 * The session generation tells the two apart. It advances both when a session ends and when the next
 * starts from idle, so a finish still owns the session's effects exactly while the generation it ended
 * at is the current one. A subsystem stop owns them regardless: nothing newer can be coming.
 */
object CameraTeardown {

    /**
     * True when the finish that ended [endedGeneration] may still reset the stream readiness, put the
     * light out and end the stream. [stopping] is the camera subsystem itself coming down.
     */
    fun ownsSessionGlobals(stopping: Boolean, endedGeneration: Long, currentGeneration: Long): Boolean =
        stopping || endedGeneration == currentGeneration
}
