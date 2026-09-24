package io.github.maxlyth.hapaneld.hardware

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure, monotone transfer function from a requested level on Home Assistant's 0..255 scale to a hardware
 * value on a node's own 0..max scale, with its inverse for the report path. It linearises a non-linear
 * actuator (PWM luminance, logarithmic perception, a crowded low end, a minimum-on threshold) so the HA
 * slider's visible change is spread across its whole travel. No Android deps; unit-tested like [PanelHealth].
 *
 * Shape: `0 → 0` always (0 still means off). For `v > 0` the hardware fraction is
 * `floor + (1 − floor) · shape(v / 255)`, so the lowest non-zero request lands at [floor] and stays
 * visible, and 255 lands at the node maximum. A non-zero request never maps to hardware 0.
 *
 * [Identity] is byte-identical to the historic linear scaling (`v · max / 255` truncated forward,
 * `h · 255 / max` truncated back), so an unprofiled panel is unchanged.
 *
 * Round trip: [toHardware] is non-decreasing, so a steep-low-end curve maps several requests onto one
 * hardware value. [toLevel] therefore returns the smallest request that reaches a hardware value (the node
 * maximum always reads as 255), which guarantees `toHardware(toLevel(h)) == h` for every reachable `h` on a
 * shaped curve ([Identity] keeps the historic truncation instead); an exact read-back of the requested
 * level is the commanded path's job (the Android setting stays on the HA scale).
 */
sealed class TransferCurve {
    /** Hardware floor for any non-zero request, as a fraction of the node maximum (0.0 = none). */
    abstract val floor: Double

    /** Shape on the unit interval: monotone non-decreasing, `shape(0) = 0`, `shape(1) = 1`. */
    protected abstract fun shape(x: Double): Double

    open fun toHardware(level: Int, maximum: Int = 255): Int {
        if (maximum <= 0) return 0
        val v = level.coerceIn(0, 255)
        if (v == 0) return 0
        val fraction = floor + (1.0 - floor) * shape(v / 255.0).coerceIn(0.0, 1.0)
        return (fraction * maximum).roundToInt().coerceIn(1, maximum)
    }

    open fun toLevel(hardware: Int, maximum: Int = 255): Int {
        if (maximum <= 0 || hardware <= 0) return 0
        if (hardware >= maximum) return 255
        val h = hardware
        // Smallest request whose hardware value reaches h (monotone forward map, so a binary search).
        var lo = 1
        var hi = 255
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (toHardware(mid, maximum) >= h) hi = mid else lo = mid + 1
        }
        // h between two reachable values: take whichever neighbour is nearer in hardware units.
        if (lo > 1 && h - toHardware(lo - 1, maximum) < toHardware(lo, maximum) - h) return lo - 1
        return lo
    }

    /** Straight linear passthrough, exactly the historic integer scaling. */
    object Identity : TransferCurve() {
        override val floor = 0.0
        override fun shape(x: Double) = x
        override fun toHardware(level: Int, maximum: Int): Int {
            if (maximum <= 0) return 0
            return (level.coerceIn(0, 255).toLong() * maximum / 255).toInt().coerceIn(0, maximum)
        }
        override fun toLevel(hardware: Int, maximum: Int): Int {
            if (maximum <= 0) return 0
            return (hardware.toLong() * 255 / maximum).toInt().coerceIn(0, 255)
        }
    }

    /** Power law `x^exponent`; 2.2 is the conventional perceptual correction for a linear-luminance PWM. */
    data class Gamma(val exponent: Double, override val floor: Double = 0.0) : TransferCurve() {
        init {
            require(exponent in MIN_GAMMA..MAX_GAMMA) { "gamma exponent must be within $MIN_GAMMA..$MAX_GAMMA" }
            require(floor in 0.0..MAX_FLOOR) { "floor must be within 0..${MAX_FLOOR}" }
        }
        override fun shape(x: Double) = x.pow(exponent)
    }

    /**
     * Piecewise-linear through measured control points `(request, hardware)`, both on the 0..255 scale.
     * The first point is `(0, 0)`, the last `(255, 255)`; requests strictly increase and hardware never
     * decreases, so the curve is monotone by construction.
     */
    data class Points(val points: List<Pair<Int, Int>>, override val floor: Double = 0.0) : TransferCurve() {
        init {
            require(points.size >= 2) { "at least two control points are required" }
            require(points.first() == (0 to 0)) { "the first control point must be [0, 0]" }
            require(points.last() == (255 to 255)) { "the last control point must be [255, 255]" }
            require(points.zipWithNext().all { (a, b) -> b.first > a.first }) { "control-point requests must strictly increase" }
            require(points.zipWithNext().all { (a, b) -> b.second >= a.second }) { "control-point hardware values must not decrease" }
            require(points.all { (i, o) -> i in 0..255 && o in 0..255 }) { "control points must lie within 0..255" }
            require(floor in 0.0..MAX_FLOOR) { "floor must be within 0..${MAX_FLOOR}" }
        }

        override fun shape(x: Double): Double {
            val v = x * 255.0
            val upper = points.indexOfFirst { it.first >= v }.coerceAtLeast(1)
            val (x0, y0) = points[upper - 1]
            val (x1, y1) = points[upper]
            val t = (v - x0) / (x1 - x0)
            return (y0 + t * (y1 - y0)) / 255.0
        }
    }

    companion object {
        const val MIN_GAMMA = 0.2
        const val MAX_GAMMA = 5.0
        /** A floor is a visibility threshold, never most of the range. */
        const val MAX_FLOOR = 0.5
        /** Named curve: perceptual gamma. */
        const val PERCEPTUAL_GAMMA = 2.2

        /**
         * Builds a curve from the profile schema's fields, or throws [IllegalArgumentException] naming the
         * defect. [name] is `identity`, `perceptual`, `gamma` or `points`; [floor] is on the 0..255 scale.
         */
        fun from(name: String, gamma: Double? = null, points: List<Pair<Int, Int>>? = null, floor: Int? = null): TransferCurve {
            require(floor == null || floor in 0..(MAX_FLOOR * 255).toInt()) { "floor must be within 0..${(MAX_FLOOR * 255).toInt()}" }
            val f = (floor ?: 0) / 255.0
            require(name == "gamma" || gamma == null) { "gamma is only valid with transfer: gamma" }
            require(name == "points" || points == null) { "points is only valid with transfer: points" }
            return when (name) {
                "identity" -> {
                    require(floor == null) { "an identity transfer takes no floor" }
                    Identity
                }
                "perceptual" -> Gamma(PERCEPTUAL_GAMMA, f)
                "gamma" -> Gamma(requireNotNull(gamma) { "transfer: gamma needs a gamma exponent" }, f)
                "points" -> Points(requireNotNull(points) { "transfer: points needs control points" }, f)
                else -> throw IllegalArgumentException("unknown transfer '$name'")
            }
        }
    }
}
