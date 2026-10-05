package io.panelassistant.android.hardware

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Pure, deterministic frame generator for the LED effects exposed through Home Assistant's built-in
 * light `effect_list` (strobe / blink / pulse). No Android deps, so it unit-tests like [PanelHealth].
 * The coroutine driver [io.panelassistant.android.control.LedEffectController] calls [frame] once per
 * [Effect.stepMs] tick and pushes the result to the LED HAL.
 *
 * Colour comes from HA's own light colour control (the last commanded r/g/b) and brightness is HA's
 * brightness (both 0..255) — HA's `effect` is a bare name with no parameters, so per-effect timing is a
 * fixed sensible constant, not a tunable.
 *
 * [PULSE] runs at ~25 Hz: fast enough that a smooth breath is never *under-sampled* — a low frame rate
 * makes the ramp skip several of a 4-bit LED's 16 brightness levels per frame, so it lurches instead of
 * gliding. 25 Hz walks every level in turn while still capping a daemon-backed LED (one blocking socket
 * write per frame) at ≈25 writes/s during the effect. strobe/blink are on/off, so they stay coarse.
 */
object LedEffects {

    /** The supported effects. [effectName] is the exact HA `effect_list` string; [stepMs] the tick. */
    enum class Effect(val effectName: String, val stepMs: Long) {
        STROBE("strobe", 80L),   // aggressive ~6 Hz on/off — attention-grabbing (alarm use case)
        BLINK("blink", 500L),    // gentle ~1 Hz on/off
        PULSE("pulse", 40L);     // breathing brightness ramp, 25 Hz, 2.4 s cycle (PULSE_STEPS × stepMs)

        companion object {
            /** The [Effect] for an HA effect name, or null for "none"/blank/unknown (= no effect). */
            fun from(name: String?): Effect? {
                val n = name?.trim() ?: return null
                return entries.firstOrNull { it.effectName.equals(n, ignoreCase = true) }
            }
        }
    }

    /** One rendered frame: an RGB triple (brightness already baked in) or the LED off. */
    sealed interface Frame {
        data class Rgb(val r: Int, val g: Int, val b: Int) : Frame
        object Off : Frame
    }

    /** Ticks in one full pulse (breathing) cycle: brightness eases up over the first half, down the second. */
    const val PULSE_STEPS = 60

    /**
     * The frame for [effect] at 0-based [step], over base colour [r]/[g]/[b] scaled by brightness [br]
     * (all 0..255). strobe/blink alternate a lit frame with [Frame.Off]; pulse breathes the colour in and
     * out along a raised-cosine (smooth, symmetric, no sharp reversal at the turns).
     *
     * These are the values HA *requested* (0..255) — the effect does no hardware linearisation. Perceptual
     * gamma and per-channel colour correction belong to the light-control layer (a per-[DeviceProfile]
     * transfer function, applied to every LED write), so solid colours and effects share one correction;
     * doing it here would only fix the effect and would double-correct once that map lands.
     */
    fun frame(effect: Effect, step: Int, r: Int, g: Int, b: Int, br: Int): Frame = when (effect) {
        Effect.STROBE, Effect.BLINK -> if (step % 2 == 0) lit(r, g, b, br) else Frame.Off
        Effect.PULSE -> {
            val phase = ((step % PULSE_STEPS) + PULSE_STEPS) % PULSE_STEPS
            val eased = (1.0 - cos(2.0 * PI * phase / PULSE_STEPS)) / 2.0   // 0 → 1 → 0, smooth at both ends
            lit(r, g, b, (br * eased).roundToInt())
        }
    }

    private fun lit(r: Int, g: Int, b: Int, br: Int): Frame.Rgb =
        br.coerceIn(0, 255).let { level ->
            Frame.Rgb(
                r.coerceIn(0, 255) * level / 255,
                g.coerceIn(0, 255) * level / 255,
                b.coerceIn(0, 255) * level / 255,
            )
        }
}
