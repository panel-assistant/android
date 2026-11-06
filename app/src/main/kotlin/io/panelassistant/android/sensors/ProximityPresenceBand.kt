package io.panelassistant.android.sensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Runtime-only ranged presence. Explicit calibration and deliberate-wave thresholds stay unchanged. */
internal class ProximityPresenceBand(
    private val clearRaw: Float,
    private val nearRaw: Float,
    initialQuiet: QuietBand? = null,
) {
    data class QuietBand(val mean: Double, val variance: Double, val count: Int) {
        init {
            require(mean.isFinite() && variance.isFinite() && variance >= 0 && count > 0)
        }

        companion object {
            fun from(values: FloatArray, count: Int = values.size): QuietBand? {
                if (count !in 1..values.size) return null
                var mean = 0.0
                var m2 = 0.0
                for (i in 0 until count) {
                    if (!values[i].isFinite()) return null
                    val delta = values[i].toDouble() - mean
                    mean += delta / (i + 1)
                    m2 += delta * (values[i] - mean)
                }
                return QuietBand(mean, if (count > 1) max(0.0, m2 / (count - 1)) else 0.0, count)
            }
        }
    }

    data class Snapshot(
        val near: Boolean?,
        val approach: Boolean,
        val level: Int?,
        val quiet: QuietBand,
        val pending: Boolean,
    )

    private val direction = if (nearRaw > clearRaw) 1.0 else -1.0
    private val span = abs(nearRaw.toDouble() - clearRaw)
    private var quiet = initialQuiet ?: QuietBand(clearRaw.toDouble(), span * span / 36.0, EFFECTIVE_SAMPLES)
    private var measuredQuiet = initialQuiet != null
    private var bootstrapQuietBlocks = if (initialQuiet == null) 0 else QUIET_BOOTSTRAP_BLOCKS
    private var bootstrapResidualVariance = 0.0
    private var near: Boolean? = null
    private var approach = false
    private var latestRaw: Float? = null
    private var blockStarted: Long? = null
    private var lastSampleAt: Long? = null
    private var lastTime: Long? = null
    private var blockCount = 0
    private var blockMean = 0.0
    private var blockM2 = 0.0
    private var blockLive = true
    private var pending: Boolean? = null
    private var pendingBlocks = 0
    private var pendingLive = true
    private var heldSettled = false
    private var occupiedReference: QuietBand? = null
    private var entryShift = 0.0
    private var pendingQuietVariance: QuietBand? = null

    init {
        require(clearRaw.isFinite() && nearRaw.isFinite() && span > 0 && span.isFinite())
    }

    fun snapshot() = Snapshot(near, approach, latestRaw?.let {
        (((it.toDouble() - quiet.mean) * direction / span) * 100.0).coerceIn(0.0, 100.0).roundToInt()
    }.takeIf { near != null }, quiet, pending != null)

    /** Only fresh acquisitions contribute evidence; a held value supplied by a timer never does. */
    fun observe(raw: Float, now: Long, fresh: Boolean = true, live: Boolean = true): Snapshot {
        approach = false
        if (!raw.isFinite() || now < 0 || lastTime?.let { now < it } == true) return sourceUnavailable()
        lastTime = now
        if (!fresh) return snapshot()
        latestRaw = raw
        if (lastSampleAt?.let { now - it < SAMPLE_INTERVAL_MS } == true) return snapshot()
        // Sparse observations cannot turn a several-minute gap into a densely observed hold.
        if (lastSampleAt?.let { now - it > BLOCK_MS } == true) {
            clearBlock()
            clearPending()
            pendingQuietVariance = null
        }
        lastSampleAt = now
        heldSettled = false
        val start = blockStarted
        // Slower genuine acquisitions extend a block until four samples exist (at most eight
        // seconds at the admitted two-second cadence). A timer never fills the missing samples.
        if (start != null && now - start >= BLOCK_MS && blockCount >= MINIMUM_SAMPLES) {
            completeBlock(durationMs = now - start)
            clearBlock()
        }
        if (blockStarted == null) blockStarted = now
        blockCount++
        val delta = raw.toDouble() - blockMean
        blockMean += delta / blockCount
        blockM2 += delta * (raw - blockMean)
        blockLive = blockLive && live
        return snapshot()
    }

    /** Only a caller that separately proves a live on-change plateau may admit held evidence. */
    fun tick(now: Long, heldEvidence: Boolean = false): Snapshot {
        approach = false
        if (now < 0 || lastTime?.let { now < it } == true) return sourceUnavailable()
        lastTime = now
        if (heldEvidence && !heldSettled && blockStarted?.let { now - it >= BLOCK_MS * CONFIRM_BLOCKS } == true && blockCount > 0) {
            completeBlock(held = true, durationMs = now - checkNotNull(blockStarted))
            heldSettled = true
        }
        return snapshot()
    }

    fun needsTick(): Boolean = blockCount > 0 && !heldSettled

    fun learnedQuiet(): QuietBand? = quiet.takeIf { measuredQuiet }

    fun sourceUnavailable(): Snapshot {
        near = null
        approach = false
        latestRaw = null
        lastSampleAt = null
        lastTime = null
        heldSettled = false
        occupiedReference = null
        entryShift = 0.0
        pendingQuietVariance = null
        clearBlock()
        clearPending()
        return snapshot()
    }

    private fun completeBlock(held: Boolean = false, durationMs: Long = BLOCK_MS) {
        val observed = QuietBand(blockMean, if (blockCount > 1) max(0.0, blockM2 / (blockCount - 1)) else quiet.variance, blockCount)
        if (!measuredQuiet && !held) {
            // The saved span is only a bootstrap uncertainty estimate. Measure the first block's
            // noise before deciding whether it is clear, while retaining the saved clear anchor.
            // Otherwise a stationary person could first be called clear, then "arrive" solely
            // because the provisional variance shrank beneath an unchanged reading.
            quiet = QuietBand(quiet.mean, observed.variance, observed.count)
            measuredQuiet = true
        }
        val widenedQuiet = if (!held && !within(quiet, observed) && meanWithinQuiet(quiet, observed)) {
            val previous = pendingQuietVariance
            pendingQuietVariance = observed
            if (previous != null && within(previous, observed) && within(observed, previous)) {
                // Increased noise needs two compatible fresh blocks around the existing clear
                // mean. While occupied, this remains provisional until clearing is confirmed.
                QuietBand(quiet.mean, max(previous.variance, observed.variance), max(quiet.count, observed.count))
            } else null
        } else {
            pendingQuietVariance = null
            null
        }
        val comparisonQuiet = widenedQuiet ?: quiet
        val delta = (observed.mean - quiet.mean) * direction
        val enter = separation(comparisonQuiet, observed)
        val quietVariance = !measuredQuiet || observed.variance <= comparisonQuiet.variance * 4 + resolution(comparisonQuiet, observed).let { it * it }
        // A single held plateau cannot measure noise. Before any real block, only the saved
        // clear anchor itself establishes clear; a wide saved span must not certify an offset
        // occupied plateau and later manufacture an arrival when actual variance becomes known.
        val heldClearAnchored = !held || measuredQuiet || abs(delta) <= resolution(quiet, observed)
        val clear = delta <= enter * CLEAR_RATIO && quietVariance && heldClearAnchored
        if (clear && !held && near != true && widenedQuiet == null && bootstrapQuietBlocks < QUIET_BOOTSTRAP_BLOCKS) {
            // Quiet noise includes variation between block means, not only jitter within a
            // two-second block. Cold startup estimates this from actual clear-qualified blocks;
            // suspected occupied blocks cannot broaden the learned clear band.
            bootstrapQuietBlocks++
            bootstrapResidualVariance += observed.variance + (observed.mean - quiet.mean).let { it * it }
            quiet = QuietBand(quiet.mean, bootstrapResidualVariance / bootstrapQuietBlocks, observed.count)
        }
        val shifted = delta > enter
        val occupied = occupiedReference
        val reversal = occupied?.let { (it.mean - observed.mean) * direction } ?: 0.0
        val departed = near == true && occupied != null && quietVariance &&
            reversal >= entryShift * DEPARTURE_RATIO && reversal > separation(occupied, observed)
        val target = when {
            clear || departed -> false
            shifted -> true
            else -> null
        }
        if (target == null) {
            clearPending()
            return
        }
        if (target == near) {
            clearPending()
            if (!target && !held) {
                acceptWiderQuiet(widenedQuiet)
                learnQuiet(observed, enter, durationMs)
            }
            if (target && !held && occupied != null && quietVariance &&
                abs(observed.mean - occupied.mean) <= separation(occupied, observed)) {
                // Track only slow changes in the occupied reference. The clear baseline remains
                // frozen, and a partial retreat beyond the noise band freezes this reference too.
                occupiedReference = observed
            }
            return
        }
        if (pending != target) {
            pending = target
            pendingBlocks = 0
            pendingLive = true
        }
        // A trusted single held measurement proves elapsed stability, not additional independent samples.
        // Its statistical uncertainty above still uses its actual observation count.
        pendingBlocks += if (held) CONFIRM_BLOCKS else 1
        pendingLive = pendingLive && blockLive
        if (pendingBlocks < CONFIRM_BLOCKS) return
        approach = target && near == false && pendingLive
        near = target
        clearPending()
        if (target) {
            occupiedReference = observed
            entryShift = max(delta, enter)
        } else {
            occupiedReference = null
            entryShift = 0.0
            if (departed && !clear) {
                // A sustained reversal of the measured entrance supplies a new clear anchor after
                // environmental drift while occupied. No stationary occupied block can do this.
                quiet = observed
                measuredQuiet = true
            } else if (!held) {
                acceptWiderQuiet(widenedQuiet)
                learnQuiet(observed, enter, durationMs)
            }
        }
    }

    private fun acceptWiderQuiet(widened: QuietBand?) {
        if (widened == null) return
        quiet = widened
        bootstrapQuietBlocks = QUIET_BOOTSTRAP_BLOCKS
    }

    private fun learnQuiet(observed: QuietBand, threshold: Double, durationMs: Long) {
        quiet = follow(quiet, observed, threshold, durationMs)
        measuredQuiet = true
    }

    private fun follow(anchor: QuietBand, observed: QuietBand, threshold: Double, durationMs: Long): QuietBand {
        val alpha = (durationMs.toDouble() / DRIFT_TIME_MS).coerceAtMost(.1)
        val step = ((observed.mean - anchor.mean) * alpha).coerceIn(-threshold * alpha, threshold * alpha)
        return QuietBand(
            anchor.mean + step,
            anchor.variance + (observed.variance - anchor.variance) * alpha + alpha * (1 - alpha) * (observed.mean - anchor.mean).let { it * it },
            max(anchor.count, observed.count),
        )
    }

    private fun clearBlock() {
        blockStarted = null
        blockCount = 0
        blockMean = 0.0
        blockM2 = 0.0
        blockLive = true
    }

    private fun clearPending() {
        pending = null
        pendingBlocks = 0
        pendingLive = true
    }

    companion object {
        const val BLOCK_MS = 2_000L
        const val SAMPLE_INTERVAL_MS = 250L
        private const val MINIMUM_SAMPLES = 4
        private const val EFFECTIVE_SAMPLES = 4
        private const val CONFIRM_BLOCKS = 2
        private const val DRIFT_TIME_MS = 120_000L
        private const val CLEAR_RATIO = .6
        private const val DEPARTURE_RATIO = .7
        private const val QUIET_BOOTSTRAP_BLOCKS = 4

        /** Shared setup/runtime test of sustained separation, independent of the raw unit and polarity. */
        fun separated(clear: QuietBand, other: QuietBand): Boolean =
            clear.count >= MINIMUM_SAMPLES && other.count >= MINIMUM_SAMPLES &&
                abs(other.mean - clear.mean) > separation(clear, other)

        /** A noisy or undersampled return is not affirmative evidence that the area cleared. */
        fun within(clear: QuietBand, other: QuietBand): Boolean =
            meansWithin(clear, other) && other.variance <= clear.variance * 4 + resolution(clear, other).let { it * it }

        fun meansWithin(clear: QuietBand, other: QuietBand): Boolean =
            clear.count >= MINIMUM_SAMPLES && other.count >= MINIMUM_SAMPLES &&
                abs(other.mean - clear.mean) <= separation(clear, other) * CLEAR_RATIO

        /** Variance expansion cannot use the candidate's own noise to excuse a changed mean. */
        fun meanWithinQuiet(quiet: QuietBand, observed: QuietBand): Boolean =
            quiet.count >= MINIMUM_SAMPLES && observed.count >= MINIMUM_SAMPLES &&
                abs(observed.mean - quiet.mean) <= max(
                    3.0 * sqrt(quiet.variance / min(quiet.count, EFFECTIVE_SAMPLES)),
                    resolution(quiet, observed),
                ) * CLEAR_RATIO

        private fun separation(clear: QuietBand, other: QuietBand): Double {
            // At most four independent observations are credited, however fast the source callbacks.
            // Blocks and thinning bound repeated-value evidence; this is deliberately not a p-value.
            val clearN = min(clear.count, EFFECTIVE_SAMPLES).toDouble()
            val otherN = min(other.count, EFFECTIVE_SAMPLES).toDouble()
            val statistical = 3.0 * sqrt(clear.variance / clearN + other.variance / otherN)
            return max(statistical, resolution(clear, other))
        }

        private fun resolution(clear: QuietBand, other: QuietBand): Double =
            max(Math.ulp(clear.mean.toFloat()), Math.ulp(other.mean.toFloat())).toDouble() * 4
    }
}
