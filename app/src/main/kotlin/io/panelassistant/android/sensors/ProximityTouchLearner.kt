package io.panelassistant.android.sensors

import io.panelassistant.android.sensors.ProximityPresenceBand.QuietBand
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learns a ranged source's present and hand levels from screen touches, and its clear level from steady
 * plateaus, so a panel calibrates itself in use. A touch proves a person is at the panel: steady readings
 * shortly before and after it are "present", and the reading as the hand meets the glass is the hand
 * (wave) level. Clear is the steady plateau furthest from those, never the first plateau seen, because a
 * panel can start with someone already in front of it. Pure and clock-free: callers pass elapsed times.
 */
internal class ProximityTouchLearner(private val seed: Seed? = null) {
    /** Wizard anchors that Hybrid starts from; touches refine them and outvote them over time. */
    data class Seed(val clearRaw: Float, val presentRaw: Float, val waveRaw: Float?)

    data class Model(
        val clear: QuietBand,
        val presentRaw: Float,
        val waveRaw: Float?,
        val seenTouches: Int,
        val unseenTouches: Int,
    ) {
        val nearIsHigher: Boolean get() = presentRaw > clear.mean
    }

    /** What one touch showed. Bands are null when their window was not a steady, well-sampled plateau. */
    private data class Record(val at: Long, val pre: QuietBand?, val post: QuietBand?, val reachMin: Float, val reachMax: Float)

    private data class Pending(val at: Long, val woke: Boolean, var keepPost: Boolean = true)

    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val plateaus = ArrayDeque<QuietBand>()
    private val records = ArrayDeque<Record>()
    private val pending = ArrayDeque<Pending>()
    private var plateauStarted: Long? = null
    private val plateauFirst = mutableListOf<Float>()
    private val plateauSecond = mutableListOf<Float>()
    private var lastSampleAt: Long? = null
    private var clearAnchor: QuietBand? = null
    private var clearAnchorAt = 0L
    private var excludedFrom = Long.MIN_VALUE
    private var excludedUntil = Long.MIN_VALUE
    private var suspended = false
    private var model: Model? = null

    fun model(): Model? = model

    /** A fresh acquisition. Held values or timer ticks must not be passed: they are not evidence. */
    fun observe(raw: Float, now: Long): Boolean {
        if (!raw.isFinite()) return false
        if (lastSampleAt?.let { now < it } == true) return false
        if (lastSampleAt?.let { now - it < ProximityPresenceBand.SAMPLE_INTERVAL_MS } == true) return finalise(now)
        if (lastSampleAt?.let { now - it > ProximityPresenceBand.BLOCK_MS * 2 } == true) {
            // A gap breaks continuity: windows spanning it would mix unrelated moments.
            samples.clear(); resetPlateau()
        }
        lastSampleAt = now
        samples.addLast(now to raw)
        while (samples.isNotEmpty() && now - samples.first().first > RING_MS) samples.removeFirst()
        accumulatePlateau(raw, now)
        return finalise(now)
    }

    /**
     * A screen touch. [woke] marks a tap that woke a dark screen: only the approach before it is used,
     * because display state can shift a proximity reading.
     */
    fun touch(now: Long, woke: Boolean = false) {
        if (suspended || now in excludedFrom until excludedUntil) return
        // Readings around a second touch include its hand: the earlier touch loses its after-window and
        // the later one its before-window.
        val previous = pending.lastOrNull()
        val overlapsPrevious = previous != null && now - previous.at < POST_END_MS + -PRE_START_MS
        if (overlapsPrevious) checkNotNull(previous).keepPost = false
        pending.addLast(Pending(now, woke, keepPost = !woke))
        if (overlapsPrevious) preSuppressed.add(now)
        // Steady time just before a touch is the approach, not clear.
        resetPlateau()
    }

    private val preSuppressed = mutableSetOf<Long>()

    /** Touches in [from, until) are ignored: they follow a wake the sensor itself caused, so are circular. */
    fun exclude(from: Long, until: Long) {
        excludedFrom = from
        excludedUntil = until
        pending.removeAll { it.at in from until until }
    }

    /** While setup runs, touches answer its prompts rather than show normal use. */
    fun suspend(active: Boolean) {
        suspended = active
        if (active) pending.clear()
    }

    private fun finalise(now: Long): Boolean {
        var changed = false
        while (pending.isNotEmpty() && now - pending.first().at >= POST_END_MS) {
            val touch = pending.removeFirst()
            val suppressPre = preSuppressed.remove(touch.at)
            val reach = window(touch.at + PRE_END_MS, touch.at + REACH_END_MS)
            if (reach.isEmpty()) continue
            val record = Record(
                at = touch.at,
                pre = if (suppressPre) null else steadyBand(window(touch.at + PRE_START_MS, touch.at + PRE_END_MS)),
                post = if (touch.keepPost) steadyBand(window(touch.at + POST_START_MS, touch.at + POST_END_MS)) else null,
                reachMin = reach.min(), reachMax = reach.max(),
            )
            records.addLast(record)
            while (records.size > MAX_RECORDS) records.removeFirst()
            changed = true
        }
        if (changed) recompute(now)
        return changed
    }

    private fun window(from: Long, until: Long): List<Float> =
        samples.filter { it.first >= from && it.first < until }.map { it.second }

    /** A steady window: enough samples, and its two halves agree, so it is a plateau and not a ramp. */
    private fun steadyBand(values: List<Float>): QuietBand? {
        if (values.size < MIN_WINDOW_SAMPLES * 2) return null
        val half = values.size / 2
        val first = QuietBand.from(values.subList(0, half).toFloatArray()) ?: return null
        val second = QuietBand.from(values.subList(half, values.size).toFloatArray()) ?: return null
        // Not separated, rather than within a fraction of the noise: integer sources quantise a quiet
        // plateau's halves a count apart.
        if (ProximityPresenceBand.separated(first, second)) return null
        return QuietBand.from(values.toFloatArray())
    }

    private fun accumulatePlateau(raw: Float, now: Long) {
        val start = plateauStarted ?: now.also { plateauStarted = it }
        if (now - start < PLATEAU_MS / 2) plateauFirst.add(raw) else plateauSecond.add(raw)
        if (now - start < PLATEAU_MS) return
        val steady = steadyBand(plateauFirst + plateauSecond)
        if (steady != null && plateauFirst.size >= MIN_WINDOW_SAMPLES && plateauSecond.size >= MIN_WINDOW_SAMPLES) {
            plateaus.addLast(steady)
            while (plateaus.size > MAX_PLATEAUS) plateaus.removeFirst()
            if (records.isNotEmpty() || seed != null) recompute(now)
        }
        resetPlateau()
    }

    private fun resetPlateau() {
        plateauStarted = null; plateauFirst.clear(); plateauSecond.clear()
    }

    /** +1 when a hand raises the reading, -1 when it lowers it, null until touches agree. */
    private fun polarity(): Int? {
        var up = 0
        var down = 0
        seed?.let { if (it.presentRaw > it.clearRaw) up += SEED_WEIGHT else down += SEED_WEIGHT }
        for (record in records) {
            val present = presentMean(record) ?: continue
            val high = record.reachMax - present
            val low = present - record.reachMin
            if (high > low) up++ else if (low > high) down++
        }
        val total = up + down
        if (total == 0) return null
        return when {
            up * 3 >= total * 2 -> 1
            down * 3 >= total * 2 -> -1
            else -> null
        }
    }

    private fun presentMean(record: Record): Double? {
        val bands = listOfNotNull(record.pre, record.post)
        return if (bands.isEmpty()) null else bands.sumOf { it.mean } / bands.size
    }

    /** The steady plateau furthest toward clear, corroborated by a second plateau that agrees with it. */
    private fun clearCandidate(direction: Int, presentGuess: Double?): QuietBand? = plateaus
        .filter { candidate -> plateaus.any { it !== candidate && agree(candidate, it, presentGuess) } }
        .minByOrNull { it.mean * direction }

    /** Two plateaus show the same state: inseparable by noise, or close relative to the presence gap. */
    private fun agree(a: QuietBand, b: QuietBand, presentGuess: Double?): Boolean =
        !ProximityPresenceBand.separated(a, b) ||
            presentGuess != null && abs(a.mean - b.mean) <= abs(presentGuess - a.mean) * AGREEMENT_OF_GAP

    private fun recompute(now: Long) {
        val direction = polarity() ?: return
        val presentGuess = records.mapNotNull(::presentMean).toMutableList()
            .apply { seed?.let { add(it.presentRaw.toDouble()) } }.takeIf { it.isNotEmpty() }?.median()
        val candidate = clearCandidate(direction, presentGuess)
        val anchor = clearAnchor
        if (candidate != null && (anchor == null || candidate.mean * direction <= anchor.mean * direction ||
                agree(anchor, candidate, presentGuess) || now - clearAnchorAt > ANCHOR_REFRESH_MS)) {
            clearAnchor = candidate
            clearAnchorAt = now
        }
        val clear = clearAnchor ?: seed?.let { QuietBand(it.clearRaw.toDouble(), 0.0, MIN_WINDOW_SAMPLES) } ?: return
        val present = mutableListOf<Double>()
        val waves = mutableListOf<Double>()
        var seen = 0
        var unseen = 0
        repeat(seed?.let { SEED_WEIGHT } ?: 0) {
            present.add(checkNotNull(seed).presentRaw.toDouble())
            seed.waveRaw?.let { waves.add(it.toDouble()) }
        }
        for (record in records) {
            val presentBands = listOfNotNull(record.pre, record.post).filter {
                (it.mean - clear.mean) * direction > 0 && ProximityPresenceBand.separated(clear, it)
            }
            val extreme = (if (direction > 0) record.reachMax else record.reachMin).toDouble()
            val handSeen = (extreme - clear.mean) * direction > 0 &&
                ProximityPresenceBand.separated(clear, QuietBand(extreme, clear.variance, MIN_WINDOW_SAMPLES))
            if (presentBands.isEmpty() && !handSeen) { unseen++; continue }
            if (presentBands.isNotEmpty()) {
                seen++
                present.add(presentBands.sumOf { it.mean } / presentBands.size)
            }
            if (handSeen) waves.add(extreme)
        }
        val qualifies = (seed != null || seen >= MIN_SEEN_TOUCHES) && seen + (if (seed != null) SEED_WEIGHT else 0) > unseen
        if (!qualifies || present.isEmpty()) { if (seed == null) model = null; return }
        val presentRaw = present.median()
        val waveRaw = waves.takeIf { it.size >= MIN_SEEN_TOUCHES || seed?.waveRaw != null && it.isNotEmpty() }?.median()
            ?.takeIf { wave ->
                // A standing person must sit well below the wave's release level, or a wave could never
                // arm while someone stands there, and their presence could read as a wave.
                val span = (wave - clear.mean) * direction
                span > 0 && (presentRaw - clear.mean) * direction <= span * WAVE_CLEAR_EXIT * PRESENT_MARGIN
            }
        model = Model(clear, presentRaw.toFloat(), waveRaw?.toFloat(), seen, unseen)
    }

    private fun MutableList<Double>.median(): Double {
        sort()
        return if (size % 2 == 1) this[size / 2] else (this[size / 2 - 1] + this[size / 2]) / 2
    }

    /** Durable state: touch records and the clear anchor. Plateaus and the sample ring relearn in minutes. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        clearAnchor?.let { put("clear", band(it)) }
        put("records", JSONArray().apply {
            records.forEach { r ->
                put(JSONObject().apply {
                    r.pre?.let { put("pre", band(it)) }
                    r.post?.let { put("post", band(it)) }
                    put("reachMin", r.reachMin.toDouble()); put("reachMax", r.reachMax.toDouble())
                })
            }
        })
    }

    /** Restores records saved by [toJson]; times restart, so restored records are ordered but undated. */
    fun restore(json: JSONObject, now: Long) {
        if (json.optInt("version") != 1) return
        json.optJSONObject("clear")?.let(::band)?.let { clearAnchor = it; clearAnchorAt = now }
        val saved = json.optJSONArray("records") ?: return
        records.clear()
        for (i in 0 until minOf(saved.length(), MAX_RECORDS)) {
            val o = saved.optJSONObject(i) ?: continue
            val min = o.optDouble("reachMin").toFloat()
            val max = o.optDouble("reachMax").toFloat()
            if (!min.isFinite() || !max.isFinite()) continue
            records.addLast(Record(now, o.optJSONObject("pre")?.let(::band), o.optJSONObject("post")?.let(::band), min, max))
        }
        recompute(now)
    }

    private fun band(b: QuietBand) = JSONObject().put("mean", b.mean).put("variance", b.variance).put("count", b.count)
    private fun band(o: JSONObject): QuietBand? = runCatching {
        QuietBand(o.getDouble("mean"), o.getDouble("variance"), o.getInt("count"))
    }.getOrNull()

    companion object {
        /** Before-touch window: the person standing there, ending as the hand starts to reach. */
        const val PRE_START_MS = -4_000L
        const val PRE_END_MS = -1_500L
        /** The hand meeting the glass. */
        const val REACH_END_MS = 700L
        /** After-touch window: the person still standing there once the hand is withdrawn. */
        const val POST_START_MS = 1_500L
        const val POST_END_MS = 4_000L
        /** Touches this soon after a sensor-caused wake are excluded as circular. */
        const val WAVE_WAKE_EXCLUSION_MS = 10_000L
        private const val RING_MS = 12_000L
        private const val PLATEAU_MS = 8_000L
        private const val MIN_WINDOW_SAMPLES = 4
        private const val MAX_PLATEAUS = 256
        private const val MAX_RECORDS = 12
        const val MIN_SEEN_TOUCHES = 3
        private const val SEED_WEIGHT = 2
        private const val ANCHOR_REFRESH_MS = 24 * 3_600_000L
        /** Wave hysteresis for touch-learned hand levels: a deliberate wave is weaker than a tap. */
        const val WAVE_NEAR_ENTER = .35f
        const val WAVE_CLEAR_EXIT = .15f
        private const val PRESENT_MARGIN = .8
        private const val AGREEMENT_OF_GAP = .2
    }
}
