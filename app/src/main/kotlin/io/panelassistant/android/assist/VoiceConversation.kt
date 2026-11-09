package io.panelassistant.android.assist

import io.panelassistant.android.panelassistant.VoiceTurnEvent

/**
 * What the Voice Notice shows of one exchange, decided from the pipeline's own signals so every state is
 * assertable without a view, as the restart card's is. The view only turns it into words and pixels.
 *
 * The pill follows the panel's phase. The hero is what was heard and the line the reply, each only once
 * Panel Assistant sends it; a release that predates them sends neither, and the card then shows the phase
 * and the track alone. A step of the track fills only on the event that proves it: the transcript proves
 * Heard, the final reply or a reply to play proves Understood, and the end of the turn proves Answered. A
 * later proof fills the steps before it, since nothing is understood that was not heard.
 *
 * Fed from three places, on whatever thread each runs: [begin] when the panel starts listening, [state] on
 * every phase change, [event] with each event of the turn.
 */
internal class VoiceConversation(
    /** The clock [Card.leaveAtMs] is on; the view's own delays run on uptime, so production passes that. */
    private val now: () -> Long,
) {
    /** [MISSED] when nothing was heard, [FAILED] when a command was heard and something after it broke. */
    enum class Phase { LISTENING, THINKING, REPLYING, MISSED, FAILED }

    /**
     * @property done how many of the [STEPS] are proven
     * @property live whether the exchange is under way; the step after the proven ones is then the current one
     * @property leaveAtMs when the card goes by itself, on [now]'s clock; null while the exchange is live
     */
    data class Card(
        val phase: Phase,
        val heard: String? = null,
        val reply: String? = null,
        val done: Int = 0,
        val live: Boolean = true,
        val leaveAtMs: Long? = null,
    ) {
        val failed: Boolean get() = phase == Phase.MISSED || phase == Phase.FAILED

        /** The step under way, or null once the exchange is over or failed. */
        val current: Int? get() = if (live && !failed && done < STEPS) done else null
    }

    @Volatile
    private var shown: Card? = null
    /** The code of the turn's failure, once Home Assistant has reported one. */
    private var failure: String? = null
    private var replied = false

    /** A follow-up still shows the exchange before it; the next transcript replaces it. */
    private var carried = false

    /** Told of every change, on the thread that made it. */
    @Volatile
    var changed: (() -> Unit)? = null

    /** What the card shows now, or null when it is closed or has left. */
    val card: Card? get() = shown?.takeIf { it.leaveAtMs == null || now() < it.leaveAtMs }

    /**
     * The panel has started listening. A new conversation replaces whatever the card showed. A follow-up in
     * the same conversation keeps the last exchange readable while the panel listens (the question it asked
     * may be what someone missed), with the pill and the track back at the start.
     */
    @Synchronized
    fun begin() {
        failure = null
        replied = false
        val previous = shown?.takeIf { it.live }
        carried = previous != null
        publish(Card(Phase.LISTENING, heard = previous?.heard, reply = previous?.reply))
    }

    @Synchronized
    fun state(state: VoiceState) {
        val card = shown?.takeIf { it.live } ?: return
        when (state) {
            VoiceState.PROCESSING -> if (card.phase == Phase.LISTENING) publish(card.copy(phase = Phase.THINKING))
            VoiceState.RESPONDING -> publish(card.copy(phase = Phase.REPLYING))
            // Only a failure the coordinator reports; one it keeps quiet (a duplicate wake word) ends idle. It
            // was not caught when nothing proved the command heard; anything later is another fault.
            VoiceState.ERROR -> end(
                card.copy(phase = if (failure == STT_NO_TEXT || card.done == 0) Phase.MISSED else Phase.FAILED),
            )
            // An exchange that ended with nothing heard or said, such as that duplicate wake word, leaves at
            // once: there is nothing to read.
            VoiceState.IDLE, VoiceState.OFF -> if (card.done == 0 && card.reply == null) publish(null) else end(card)
            VoiceState.LISTENING -> Unit
        }
    }

    @Synchronized
    fun event(event: VoiceTurnEvent) {
        val card = shown?.takeIf { it.live } ?: return
        when (event) {
            is VoiceTurnEvent.Transcript -> publish(
                card.copy(
                    heard = event.text,
                    reply = if (carried) null else card.reply,
                    done = maxOf(card.done, 1),
                    phase = if (card.phase == Phase.LISTENING) Phase.THINKING else card.phase,
                ),
            )
            // The final reply is authoritative: a piece still in flight never replaces it.
            is VoiceTurnEvent.Reply -> if (!replied) {
                replied = event.final
                publish(card.copy(reply = event.text, done = if (event.final) maxOf(card.done, 2) else card.done))
            }
            is VoiceTurnEvent.Play -> publish(card.copy(done = maxOf(card.done, 2)))
            is VoiceTurnEvent.Failed -> failure = event.code
            is VoiceTurnEvent.End -> if (failure == null) publish(card.copy(done = STEPS))
            VoiceTurnEvent.ListenEnd -> Unit
        }
        if (event is VoiceTurnEvent.Transcript) carried = false
    }

    /** Someone tapped the card. */
    @Synchronized
    fun close() = publish(null)

    private fun end(card: Card) = publish(card.copy(live = false, leaveAtMs = now() + leaveAfterMs(card.reply)))

    private fun publish(card: Card?) {
        shown = card
        changed?.invoke()
    }

    companion object {
        const val STEPS = 3

        /** Home Assistant's code for speech that produced no text. */
        const val STT_NO_TEXT = "stt-no-text-recognized"

        /** The reply was just spoken aloud, so the card is a reminder: a glance's worth with no reply. */
        const val LEAVE_MIN_MS = 4_000L

        /**
         * Then time to read the reply: about 17 characters a second, a slow adult reading pace (200 words a
         * minute is about 20), because the card is read from across the room.
         */
        const val READ_MS_PER_CHAR = 60L

        /** A long answer leaves after this; it was spoken in full, and the card must not linger. */
        const val LEAVE_MAX_MS = 15_000L

        fun leaveAfterMs(reply: String?): Long =
            (LEAVE_MIN_MS + (reply?.length ?: 0) * READ_MS_PER_CHAR).coerceAtMost(LEAVE_MAX_MS)
    }
}
