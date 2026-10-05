package io.panelassistant.android.mqtt

/**
 * Pure helpers for the local-state → MQTT sync ([io.panelassistant.android.MqttBridge.syncLocalState]),
 * split out of the god-object bridge so the publish-decision logic + the /diag event log are unit-testable
 * without a broker. See docs on the sync contract: publish only past a deadband AND once settled, so fast
 * oscillation publishes nothing and steady state costs zero messages.
 */
object SyncGate {
    /**
     * Publish predicate for a NUMERIC channel that HA must track when it changes outside ha-paneld's API
     * (auto-brightness, hardware volume keys, firmware node-dims). Fires when [cur] differs from the last
     * [published] value by more than [deadband] AND has **settled** — its change since the [prevTick] read
     * is within [settleBand], so a still-moving mid-ramp value waits for the next tick instead of spamming
     * intermediate steps. A negative [cur] or [published] (= unknown / not yet baselined) never fires.
     */
    fun settled(cur: Int, prevTick: Int, published: Int, deadband: Int, settleBand: Int): Boolean =
        cur >= 0 && published >= 0 && kotlin.math.abs(cur - published) > deadband &&
            (prevTick < 0 || kotlin.math.abs(cur - prevTick) <= settleBand)
}

/**
 * Bounded, newest-first ring of recent "this changed outside MQTT" events, surfaced on the info page +
 * `/diag` so an operator can see what the panel synced and when. Thread-safe: recorded on the watchdog
 * thread, read on the HTTP thread. The clock is injected (elapsed-realtime millis) so it stays pure —
 * ages are computed at read time relative to `now`.
 */
class SyncLog(private val cap: Int = CAP) {
    private data class Event(val atMs: Long, val text: String)

    private val events = ArrayDeque<Event>()

    @Synchronized
    fun record(atMs: Long, text: String) {
        events.addLast(Event(atMs, text))
        while (events.size > cap) events.removeFirst()
    }

    /** Recent events **newest-first**, each rendered as "<age>s ago · <text>" relative to [nowMs]. */
    @Synchronized
    fun recent(nowMs: Long): List<String> =
        events.reversed().map { "${((nowMs - it.atMs).coerceAtLeast(0) / 1000)}s ago · ${it.text}" }

    companion object {
        const val CAP = 8
    }
}
