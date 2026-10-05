package io.panelassistant.android.control

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * How many Wi-Fi outages the panel counted in the last 24 hours, and whether that number is a
 * floor because the storage cap had to drop older evidence still inside the window.
 */
internal data class WifiOutageCounts(
    val last24h: Int,
    val saturated: Boolean = false,
)

/**
 * Durable form: episode start instants in INSERTION order (newest last), plus the newest instant
 * the cap ever forced out.
 *
 * Insertion order, not timestamp order, is what makes bounded retention safe across a reversible
 * clock correction: eviction must remove what was recorded longest ago, never whatever happens to
 * sort earliest, or a corrected clock would discard the episodes happening now in favour of stale
 * future-dated history and hide the very thing the counter exists to show.
 */
internal data class WifiOutageRecord(
    val episodeStartsWallMs: List<Long>,
    val newestDroppedWallMs: Long = 0L,
)

internal interface WifiOutageStore {
    fun load(): WifiOutageRecord?
    fun save(record: WifiOutageRecord)
}

internal fun encodeWifiOutageRecord(record: WifiOutageRecord): String = JSONObject()
    .put("version", WifiOutageTracker.RECORD_VERSION)
    .put("episodes", JSONArray().apply { record.episodeStartsWallMs.forEach { put(it) } })
    .put("newest_dropped_wall_ms", record.newestDroppedWallMs)
    .toString()

/**
 * Rejects unknown versions wholesale — a future format is not guessed at, it starts clean.
 *
 * Scans once through a bounded window instead of materialising the whole array, so an oversized or
 * corrupt record costs bounded memory, and every instant it has to discard is folded into the
 * dropped-provenance marker rather than silently vanishing into an exact-looking count.
 */
internal fun parseWifiOutageRecord(raw: String?): WifiOutageRecord? {
    if (raw.isNullOrBlank()) return null
    // org.json materialises the whole document before anything can be bounded, so the only place to
    // bound a corrupt or imported record's memory is before it is parsed at all. A record this app
    // wrote cannot approach the limit; anything that does is not ours and starts clean.
    if (raw.length > WifiOutageTracker.MAX_RECORD_CHARS) return null
    return runCatching {
        val json = JSONObject(raw)
        if (json.getInt("version") != WifiOutageTracker.RECORD_VERSION) return null
        val array = json.getJSONArray("episodes")
        val kept = ArrayDeque<Long>()
        // Three distinct cases, none of which may silently become "nothing was dropped":
        //   * absent   — this app always writes the field, so a record without it is not one of
        //                ours; it is unsupported and the whole record is refused (start clean).
        //   * corrupt  — present but unreadable: evidence exists, its instant does not.
        //   * negative — likewise meaningless as an instant.
        // Only a readable, non-negative value is taken at face value.
        if (!json.has("newest_dropped_wall_ms")) return null
        val declaredDropped = json.optLong("newest_dropped_wall_ms", -1L)
        var newestDropped = if (declaredDropped < 0L) {
            WifiOutageTracker.UNPLACEABLE_DROP_MARKER
        } else {
            declaredDropped
        }
        for (index in 0 until array.length()) {
            kept.addLast(array.getLong(index))
            if (kept.size > WifiOutageTracker.MAX_RETAINED_EPISODES) {
                newestDropped = maxOf(newestDropped, kept.removeFirst())
            }
        }
        WifiOutageRecord(episodeStartsWallMs = kept.toList(), newestDroppedWallMs = newestDropped)
    }.getOrNull()
}

/**
 * Whether the count has crossed from background noise into "the Wi-Fi infrastructure needs
 * attention". Derived from one measured problem day on real panel hardware — eleven episodes in
 * 24 h at a healthy signal level: that day must trip the rule with margin, and a single blip must
 * never. Six is roughly half the observed bad day.
 */
internal fun wifiOutageAttention(last24h: Int): Boolean = last24h >= WifiOutageTracker.ATTENTION_24H

/**
 * Whether the instability is CHRONIC — the bar for entering the `/diag` report, which is pasted into
 * bug reports and is terse by design. The panel's own diagnostics card shows every episode, because
 * somebody reading that card is already asking about this panel; a pasted report should carry the
 * line only when the network is plausibly part of the story.
 *
 * Saturation qualifies on its own and deliberately ignores the count. It means episodes were evicted
 * at the retention bound, or that their provenance was unreadable and failed closed, so the number is
 * an explicit floor rather than a total — a state that can carry a LOW `last24h` while being the
 * worst thing this tracker can report. A rule that read only the count would omit the line from
 * exactly the panel whose report most needs it.
 */
internal fun wifiOutageChronic(counts: WifiOutageCounts): Boolean =
    counts.saturated || wifiOutageAttention(counts.last24h)

/**
 * Diagnostics row value for [counts], or null while the last 24 hours are clean — a permanent
 * "0 outages" row is noise, so a stable panel shows nothing at all.
 *
 * Pure and separate from the tracker so one `counts()` read can feed both the row and the `/diag`
 * gate: a report must never include a line that disagrees with the text beside it.
 */
internal fun wifiOutageStatusText(counts: WifiOutageCounts): String? {
    // A zero count with dropped evidence still in-window is not a clean panel: the cap threw
    // away episodes we can no longer place. Saying nothing there would hide the worst case.
    if (counts.last24h <= 0 && !counts.saturated) return null
    val base = if (counts.saturated) {
        // Saturated: the retention bound evicted episodes still inside the window, or their
        // provenance was unreadable and failed closed. The surviving number is a STRICT floor —
        // the window held more than this — so it is phrased as a lower bound and never as a rate.
        // "at least 1 outage" was the trap: truthful arithmetic that still reads as a quiet day
        // once a compacted history is all that reaches the reader.
        if (counts.last24h <= 0) {
            "more outages in the last 24 h than the panel kept records for"
        } else {
            "more than ${counts.last24h} ${outageNoun(counts.last24h)} in the last 24 h"
        }
    } else {
        "${counts.last24h} ${outageNoun(counts.last24h)} in the last 24 h"
    }
    // ONE classification drives both surfaces. Deriving the note from the raw count is what let a
    // saturated panel show a bare low number on its own card while `/diag`, which reads
    // [wifiOutageChronic], called the same moment chronic — two surfaces, one moment, opposite
    // readings. Saturation is chronic on its own, so a compacted history always carries the note.
    return if (wifiOutageChronic(counts)) {
        "$base — repeated drops; the Wi-Fi link needs attention"
    } else {
        base
    }
}

/** Agrees with the number actually printed, so a floor phrasing cannot produce "1 outages". */
private fun outageNoun(count: Int): String = if (count == 1) "outage" else "outages"

/**
 * Counts default-network Wi-Fi outages in the last 24 hours, so repeated short dropouts become a
 * standing diagnostic.
 *
 * A single 2–9 second blip should stay invisible in the moment — a panel that shouts about a
 * four-second dropout is worse than one that says nothing — but a panel dropping off Wi-Fi many
 * times a day is telling its owner the Wi-Fi needs attention, and without a counter nobody ever
 * learns that. Fed from the service's existing default-network callback; it never observes the
 * Home Assistant socket or the MQTT broker, whose restarts must not be blamed on the network.
 *
 * One episode = a Wi-Fi default network was lost and later recovered onto Wi-Fi again. Both halves
 * of that sentence are load-bearing and neither is assumed:
 *
 *  * **The transport is never guessed.** It is tri-state — Wi-Fi, not Wi-Fi, or not yet known.
 *    `onAvailable` can arrive before Android has published the new network's capabilities, so an
 *    unknown recovery is PARKED, not counted, until [onTransportChanged] answers. A later Ethernet
 *    or VPN answer therefore discards it instead of committing a Wi-Fi outage that never happened.
 *    A loss observed while the transport is unknown opens nothing at all.
 *  * **Counting happens at recovery**, so an episode still open at process death is dropped — an
 *    offline panel cannot report it anyway, and a power-cut reboot mid-outage is not a Wi-Fi blip.
 *
 * A re-loss within [MERGE_WINDOW_MS] of the last COUNTED recovery is the same disturbance
 * continuing, not a second episode; anchoring on the counted recovery (rather than every recovery)
 * is what stops a continuously flapping link chaining into one eternal episode and reporting "1".
 *
 * The window is a read filter over stored instants, never a deletion, applied identically in memory
 * and after a restart, so a reversible clock correction moves the count and moves it back. Only the
 * storage cap removes an instant, and when it does the count says it is a floor. Every counted
 * episode is persisted as it is counted, so no restart can lose one. Nothing identifying is kept:
 * episode instants and a count, never an SSID, BSSID or MAC.
 *
 * Mutated on ConnectivityManager's binder thread; read from the MQTT heartbeat and HTTP threads.
 */
internal class WifiOutageTracker(
    private val store: WifiOutageStore? = null,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Episode(val startWallMs: Long, val startElapsedMs: Long, val alreadyCounted: Boolean)

    /** A closed episode awaiting the authoritative transport of the network that ended it. */
    private class PendingRecovery(val episode: Episode, val recoveryElapsedMs: Long, val networkKey: Long)

    private val episodeStartsWallMs = mutableListOf<Long>()
    /** null = the current default network's transport is not yet known, never "assume Wi-Fi". */
    private var transportWifi: Boolean? = null
    private var openEpisode: Episode? = null
    private var pendingRecovery: PendingRecovery? = null
    private var lastRecoveryElapsedMs: Long? = null
    private var currentNetworkKey: Long? = null
    private var newestDroppedWallMs = 0L

    init {
        store?.load()?.let { record -> adopt(record) }
    }

    /**
     * Take a record as this tracker's state, normalising provenance and durably recording the
     * normalisation so a restart cannot re-anchor it and keep the panel saturated indefinitely.
     */
    private fun adopt(record: WifiOutageRecord) {
        val nowWall = wallClockMs()
        episodeStartsWallMs.clear()
        episodeStartsWallMs.addAll(record.episodeStartsWallMs)
        newestDroppedWallMs = placeableDropMarker(record.newestDroppedWallMs, nowWall)
        if (newestDroppedWallMs != record.newestDroppedWallMs) {
            store?.save(WifiOutageRecord(episodeStartsWallMs.toList(), newestDroppedWallMs))
        }
    }

    /**
     * Give an unusable provenance marker a real instant so saturation can age out.
     *
     *  * unplaceable (absent-from-our-writer, corrupt or negative) — anchor to now;
     *  * dated in the future — clamp to now, because an arbitrary future instant would otherwise pin
     *    saturation open forever, and a record we cannot trust must not outlive one window.
     *
     * The anchored value is persisted by the caller, so it does NOT slide forward on every restart:
     * a corrupt marker saturates for exactly one window from the first time it was seen.
     */
    private fun placeableDropMarker(raw: Long, nowWall: Long): Long = when {
        raw == UNPLACEABLE_DROP_MARKER -> nowWall
        raw > nowWall -> nowWall
        else -> raw
    }

    /**
     * A default network arrived, closing any open episode. [isWifi] is null when the caller could
     * not read the new network's capabilities yet; the episode then waits for [onTransportChanged]
     * rather than being counted on an assumption.
     */
    @Synchronized
    fun onDefaultAvailable(networkKey: Long) {
        // An arriving network's capabilities are NOT read here. A synchronous snapshot taken during
        // onAvailable can predate the authoritative callback, and crediting an episode from it is
        // how a Wi-Fi outage gets invented or discarded on stale information.
        currentNetworkKey = networkKey
        openEpisode?.let { open ->
            openEpisode = null
            // The pending recovery belongs to THIS network. A successor arriving before the
            // capability callback must not be allowed to resolve its predecessor's episode.
            pendingRecovery = PendingRecovery(open, elapsedRealtimeMs(), networkKey)
        }
        transportWifi = null
    }

    /** The named network's transport became known or changed. The only authoritative source. */
    @Synchronized
    fun onTransportChanged(networkKey: Long, isWifi: Boolean) {
        if (networkKey != currentNetworkKey) return
        resolveRecovery(networkKey, isWifi)
        if (!isWifi) forgetMergeAnchor()
        transportWifi = isWifi
    }

    /** The default network was lost with no replacement. Duplicate losses are one episode. */
    @Synchronized
    fun onDefaultLost() {
        // A recovery whose transport never arrived can no longer be attributed to anything: the
        // network that ended that episode is itself gone, and the next capability callback will
        // describe a different network. Drop it rather than let it be resolved by the wrong one.
        pendingRecovery = null
        if (openEpisode != null) return
        if (transportWifi != true) return
        val nowElapsed = elapsedRealtimeMs()
        val merged = lastRecoveryElapsedMs?.let { nowElapsed - it in 0..MERGE_WINDOW_MS } == true
        openEpisode = Episode(
            startWallMs = wallClockMs(),
            startElapsedMs = nowElapsed,
            alreadyCounted = merged,
        )
    }

    /**
     * Commit or discard the parked recovery now that the transport is known. A merged continuation
     * changes nothing: it is the same disturbance still going, not a second episode.
     */
    private fun resolveRecovery(networkKey: Long, isWifi: Boolean) {
        val pending = pendingRecovery ?: return
        // Only the network that ended the episode may decide what it was.
        if (pending.networkKey != networkKey) return
        pendingRecovery = null
        if (!isWifi) {
            forgetMergeAnchor()
            return
        }
        if (pending.episode.alreadyCounted) return
        lastRecoveryElapsedMs = pending.recoveryElapsedMs
        // Appended in insertion order and NEVER sorted: eviction below must drop what was recorded
        // longest ago, not whatever sorts earliest, so a backward clock correction cannot make the
        // cap throw away the episodes happening right now.
        episodeStartsWallMs.add(pending.episode.startWallMs)
        while (episodeStartsWallMs.size > MAX_RETAINED_EPISODES) {
            // Only the cap ever removes an instant. Remember the newest one it took, so the count
            // can admit it is a floor for as long as a dropped episode could still be in-window.
            newestDroppedWallMs = maxOf(newestDroppedWallMs, episodeStartsWallMs.removeAt(0))
        }
        // Persisted as it is counted: a restart must never lose an episode the panel already counted.
        store?.save(WifiOutageRecord(episodeStartsWallMs.toList(), newestDroppedWallMs))
    }

    /**
     * Any time on another transport ends the disturbance: a Wi-Fi failure ten seconds after the
     * panel came back from Ethernet is a NEW episode, not the continuation of one that ended
     * before the non-Wi-Fi interval, so the merge anchor must not survive the excursion.
     */
    private fun forgetMergeAnchor() {
        lastRecoveryElapsedMs = null
    }

    /**
     * Adopt a record written underneath this process — a settings restore replaces `app_state`
     * wholesale, and a live tracker that kept counting from memory would immediately save over the
     * restored history. The restored record wins; in-flight episode state is intentionally dropped.
     */
    @Synchronized
    fun adoptRestoredRecord() {
        val record = store?.load() ?: return
        adopt(record)
        openEpisode = null
        pendingRecovery = null
        forgetMergeAnchor()
    }

    /** The rolling count for the MQTT diagnostic sensor and the Runtime diagnostics row. */
    @Synchronized
    fun counts(): WifiOutageCounts {
        val nowWall = wallClockMs()
        // Pure read: the window is a filter, never a deletion, so a reversible clock correction
        // cannot destroy history and cannot make the count differ before and after a restart.
        return WifiOutageCounts(
            last24h = episodeStartsWallMs.count { it > nowWall - WINDOW_MS && it <= nowWall },
            // A dropped marker saturates whenever it could still be in-window, INCLUDING when a
            // clock rollback left it dated in the future: an instant we cannot place is still an
            // episode we dropped, and excluding it would present a capped count as exact.
            saturated = newestDroppedWallMs > nowWall - WINDOW_MS,
        )
    }

    /**
     * Runtime-diagnostics row value, or null while the last 24 hours are clean. Convenience over
     * [wifiOutageStatusText]; a caller that also needs the `/diag` gate reads [counts] once and calls
     * both pure functions itself rather than reading the tracker twice.
     */
    @Synchronized
    fun statusText(): String? = wifiOutageStatusText(counts())

    companion object {
        const val RECORD_VERSION = 5
        const val WINDOW_MS = 24L * 3_600_000L
        /** Observed blips recover in 2–9 s and distinct real events sit minutes apart, so only an
         *  immediate re-drop is the same disturbance. */
        const val MERGE_WINDOW_MS = 10_000L
        /** Bounds the persisted record; far above any real day, and reaching it makes the count an
         *  explicit floor rather than silently dropping events. */
        const val MAX_RETAINED_EPISODES = 200
        /** Generous ceiling for [MAX_RETAINED_EPISODES] 13-digit instants plus keys and separators. */
        const val MAX_RECORD_CHARS = 8_192
        /** Provenance present but unreadable: dropped evidence exists, its instant does not. */
        const val UNPLACEABLE_DROP_MARKER = Long.MAX_VALUE
        const val ATTENTION_24H = 6
    }
}
