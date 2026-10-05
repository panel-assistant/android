package io.panelassistant.android.control

import android.content.Context
import android.content.SharedPreferences
import io.panelassistant.android.persistence.AppState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal enum class AutoSleepLocalEvidence { TOUCH, PROXIMITY }

internal data class AutoSleepLearnedLease(
    val leaseMs: Long,
    val touchEvidenceCount: Int,
    val proximityEvidenceCount: Int,
    val correctionFloorMs: Long,
)

/**
 * Seven daily aggregate histograms; no raw touch timestamp or gesture data is persisted.
 *
 * Routine evidence is cached and written at most once per minute, then flushed when the owning
 * controller terminates. A correction after premature sleep is rare and user-visible, so it is
 * persisted immediately.
 */
internal class AutoSleepLearningStore private constructor(
    private val preferences: SharedPreferences,
    private val epochMillis: () -> Long,
    private val minimumWriteIntervalMs: Long,
) {
    constructor(context: Context, epochMillis: () -> Long = System::currentTimeMillis) : this(
        AppState.preferences(context, "auto-sleep-learning", PREFS_NAME),
        epochMillis,
        DEFAULT_WRITE_INTERVAL_MS,
    )

    internal constructor(
        preferences: SharedPreferences,
        epochMillis: () -> Long = System::currentTimeMillis,
        @Suppress("UNUSED_PARAMETER") testing: Unit = Unit,
        minimumWriteIntervalMs: Long = DEFAULT_WRITE_INTERVAL_MS,
    ) : this(preferences, epochMillis, minimumWriteIntervalMs.coerceAtLeast(0L))

    private var cachedOwner: String? = null
    private var cachedState: LearningState? = null
    private var dirty = false
    private var lastWriteAtEpochMs: Long? = null

    @Synchronized
    fun learnedLease(partition: String, baseLeaseMs: Long): AutoSleepLearnedLease {
        val now = epochMillis()
        val state = stateFor(partition)
        if (state.prune(day(now), now)) dirty = true
        flushIfDue(now)
        val touch = percentile(aggregate(state.days.map(DayEvidence::touch)), 0.90)
        val proximity = percentile(aggregate(state.days.map(DayEvidence::proximity)), 0.75)
        val correction = decayedCorrection(state, now)
        val touchLease = touch?.let { bucketUpperMs(it.first) + LEASE_MARGIN_MS }
        val proximityLease = proximity?.let { bucketUpperMs(it.first) + LEASE_MARGIN_MS }
            ?.coerceAtMost(MAX_PROXIMITY_LEARNED_MS)
        return AutoSleepLearnedLease(
            leaseMs = maxOf(
                baseLeaseMs,
                touchLease ?: MIN_AUTO_SLEEP_LEASE_MS,
                proximityLease ?: MIN_AUTO_SLEEP_LEASE_MS,
                correction,
            ).coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS),
            touchEvidenceCount = touch?.second ?: 0,
            proximityEvidenceCount = proximity?.second ?: 0,
            correctionFloorMs = correction,
        )
    }

    @Synchronized
    fun recordGap(partition: String, evidence: AutoSleepLocalEvidence, gapMs: Long) {
        if (gapMs !in 1..MAX_AUTO_SLEEP_LEASE_MS) return
        val now = epochMillis()
        val today = day(now)
        val state = stateFor(partition)
        state.prune(today, now)
        val row = state.days.firstOrNull { it.day == today } ?: DayEvidence(today).also { state.days += it }
        val bucket = ((gapMs - 1L) / BUCKET_MS).toInt().coerceIn(0, BUCKET_COUNT - 1)
        val target = if (evidence == AutoSleepLocalEvidence.TOUCH) row.touch else row.proximity
        target[bucket] = (target[bucket] + 1).coerceAtMost(MAX_BUCKET_COUNT)
        dirty = true
        flushIfDue(now)
    }

    @Synchronized
    fun recordCorrection(partition: String, floorMs: Long) {
        val now = epochMillis()
        val state = stateFor(partition)
        state.prune(day(now), now)
        state.correctionFloorMs = maxOf(state.correctionFloorMs, floorMs)
            .coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS)
        state.lastCorrectionAtEpochMs = now
        dirty = true
        flush(now)
    }

    /** Persist the latest aggregate at a lifecycle boundary. */
    @Synchronized
    fun flush() {
        flush(epochMillis())
    }

    private fun stateFor(partition: String): LearningState {
        val owner = fingerprint(partition)
        if (cachedOwner == owner) return checkNotNull(cachedState)
        // The persisted format intentionally owns one active source partition. Switching source
        // sets discards any pending evidence from the old owner rather than allowing it to return.
        cachedOwner = owner
        val persistedOwnerMatches = preferences.getString(KEY_PARTITION, "") == owner
        val loaded = read(owner)
        cachedState = loaded.state
        dirty = !persistedOwnerMatches || !loaded.valid
        lastWriteAtEpochMs = null
        return checkNotNull(cachedState)
    }

    private fun read(owner: String): LoadedState {
        if (preferences.getString(KEY_PARTITION, "") != owner) return LoadedState(LearningState())
        val raw = preferences.getString(KEY_STATE, "").orEmpty()
        if (raw.isBlank()) return LoadedState(LearningState())
        return runCatching {
            val json = JSONObject(raw)
            val state = LearningState(
                correctionFloorMs = json.optLong("correction_floor_ms", MIN_AUTO_SLEEP_LEASE_MS),
                lastCorrectionAtEpochMs = json.optLong("last_correction_at_ms", 0L),
            )
            val days = json.optJSONArray("days") ?: JSONArray()
            for (index in 0 until days.length().coerceAtMost(RETENTION_DAYS.toInt())) {
                val row = days.optJSONObject(index) ?: continue
                state.days += DayEvidence(
                    row.optLong("day"),
                    counts(row.optJSONArray("touch")),
                    counts(row.optJSONArray("proximity")),
                )
            }
            LoadedState(state)
        }.getOrElse { LoadedState(LearningState(), valid = false) }
    }

    private fun flushIfDue(now: Long) {
        if (!dirty) return
        val last = lastWriteAtEpochMs
        if (last == null || now < last || now - last >= minimumWriteIntervalMs) flush(now)
    }

    private fun flush(now: Long) {
        if (!dirty) return
        val owner = cachedOwner ?: return
        val state = cachedState ?: return
        val json = JSONObject()
            .put("correction_floor_ms", state.correctionFloorMs)
            .put("last_correction_at_ms", state.lastCorrectionAtEpochMs)
            .put("days", JSONArray().apply { state.days.sortedBy(DayEvidence::day).forEach { row ->
                put(JSONObject()
                    .put("day", row.day)
                    .put("touch", JSONArray(row.touch.toList()))
                    .put("proximity", JSONArray(row.proximity.toList())))
            } })
        preferences.edit().putString(KEY_PARTITION, owner).putString(KEY_STATE, json.toString()).apply()
        dirty = false
        lastWriteAtEpochMs = now
    }

    private fun counts(array: JSONArray?): IntArray = IntArray(BUCKET_COUNT) { index ->
        array?.optInt(index, 0)?.coerceIn(0, MAX_BUCKET_COUNT) ?: 0
    }

    private fun aggregate(rows: List<IntArray>): List<Int> = List(BUCKET_COUNT) { bucket ->
        rows.sumOf { it[bucket].toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun LearningState.prune(today: Long, now: Long): Boolean {
        var changed = days.removeAll { it.day !in (today - RETENTION_DAYS + 1)..today }
        if (lastCorrectionAtEpochMs > 0L && now - lastCorrectionAtEpochMs > RETENTION_MS) {
            correctionFloorMs = MIN_AUTO_SLEEP_LEASE_MS
            lastCorrectionAtEpochMs = 0L
            changed = true
        }
        return changed
    }

    private fun decayedCorrection(state: LearningState, now: Long): Long {
        if (state.lastCorrectionAtEpochMs <= 0L) return MIN_AUTO_SLEEP_LEASE_MS
        val age = (now - state.lastCorrectionAtEpochMs).coerceAtLeast(0L)
        val decayDays = ((age - DAY_MS).coerceAtLeast(0L) / DAY_MS).toInt()
        return (state.correctionFloorMs - decayDays * CORRECTION_DECAY_STEP_MS)
            .coerceAtLeast(MIN_AUTO_SLEEP_LEASE_MS)
    }

    /** Returns bucket index and total evidence only after the requested minimum sample count. */
    private fun percentile(counts: List<Int>, percentile: Double): Pair<Int, Int>? {
        val total = counts.sum()
        if (total < MIN_PERCENTILE_EVIDENCE) return null
        val target = kotlin.math.ceil(total * percentile).toInt().coerceAtLeast(1)
        var cumulative = 0
        counts.forEachIndexed { index, count ->
            cumulative += count
            if (cumulative >= target) return index to total
        }
        return counts.lastIndex to total
    }

    private fun bucketUpperMs(bucket: Int): Long = (bucket + 1L) * BUCKET_MS
    private fun day(epochMs: Long): Long = epochMs / DAY_MS
    private fun fingerprint(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    private data class LearningState(
        val days: MutableList<DayEvidence> = mutableListOf(),
        var correctionFloorMs: Long = MIN_AUTO_SLEEP_LEASE_MS,
        var lastCorrectionAtEpochMs: Long = 0L,
    )

    private data class LoadedState(val state: LearningState, val valid: Boolean = true)

    private data class DayEvidence(
        val day: Long,
        val touch: IntArray = IntArray(BUCKET_COUNT),
        val proximity: IntArray = IntArray(BUCKET_COUNT),
    )

    private companion object {
        const val PREFS_NAME = "ha-paneld-auto-sleep-learning"
        const val KEY_PARTITION = "partition"
        const val KEY_STATE = "state"
        const val BUCKET_COUNT = 12
        const val MAX_BUCKET_COUNT = 10_000
        const val MIN_PERCENTILE_EVIDENCE = 3
        const val RETENTION_DAYS = 7L
        const val BUCKET_MS = 5L * 60_000L
        const val LEASE_MARGIN_MS = 2L * 60_000L
        const val MAX_PROXIMITY_LEARNED_MS = 30L * 60_000L
        const val DAY_MS = 24L * 60L * 60_000L
        const val RETENTION_MS = RETENTION_DAYS * DAY_MS
        const val CORRECTION_DECAY_STEP_MS = 5L * 60_000L
        const val DEFAULT_WRITE_INTERVAL_MS = 60_000L
    }
}
