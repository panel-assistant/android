package io.panelassistant.android.hardware

/**
 * Per-channel transfer function: maps a *requested* channel value (0..255, what HA asks for) to the
 * *hardware* value written to the LED, correcting that panel's non-linear LED response so a colour comes
 * out looking the way it was requested. Carried per [io.panelassistant.android.device.DeviceProfile];
 * every LED write on the ioctl path goes through it. Pure — no Android deps, unit-tested (LedTransferTest).
 *
 * Hardware finding (probed on the WF1589T `/dev/ledjni`, 2026-07-11): the LED is **not 4-bit** — a
 * single channel ramped brightness progressively 15→31→63→127→255, so it accepts the full 0..255 range.
 * But the per-channel response is **non-linear**: driving raw 0..255 shifts the colour (red stops being
 * red), while the low-drive region reproduces colour correctly. The [Rk3576FourBit] stub therefore stays
 * in that safe region for now; the real fix is a **measured per-channel curve** that uses more of the
 * range while keeping colour accurate — drop it in here per profile without touching the controllers.
 */
interface LedTransfer {
    fun red(v: Int): Int
    fun green(v: Int): Int
    fun blue(v: Int): Int

    /** Straight passthrough (clamped 0..255). The neutral default for a linear / externally-corrected LED. */
    object Identity : LedTransfer {
        override fun red(v: Int) = v.coerceIn(0, 255)
        override fun green(v: Int) = v.coerceIn(0, 255)
        override fun blue(v: Int) = v.coerceIn(0, 255)
    }

    /**
     * STUB for the rk3576 `/dev/ledjni` LED (WF1589T). Maps 0..255 into the safe low-drive
     * region 0..15 that reproduces colour correctly (truncating, so a dimming colour sheds its weak
     * channels toward its dominant hue rather than flashing white). Same curve on every channel for now —
     * replace with a measured per-channel curve to widen the range beyond 16 levels.
     */
    object Rk3576FourBit : LedTransfer {
        private fun safe(v: Int) = (v.coerceIn(0, 255) * 15) / 255
        override fun red(v: Int) = safe(v)
        override fun green(v: Int) = safe(v)
        override fun blue(v: Int) = safe(v)
    }

    /** One [TransferCurve] on every channel, on the 0..255 scale the LED backends take. A floor applies
     *  per channel, so it lifts a mixed colour's weak channel and tints it; keep an LED floor small. */
    class Curved(private val curve: TransferCurve) : LedTransfer {
        override fun red(v: Int) = curve.toHardware(v)
        override fun green(v: Int) = curve.toHardware(v)
        override fun blue(v: Int) = curve.toHardware(v)
    }

    companion object {
        const val RK3576_FOUR_BIT = "rk3576-four-bit"

        /** The `hardware.led.transfer` names a profile may declare: the curve names plus the ioctl stub. */
        val NAMES = linkedSetOf("identity", RK3576_FOUR_BIT, "perceptual", "gamma", "points")

        /** The LED transfer for a curve; the identity curve is the clamped passthrough. */
        fun curved(curve: TransferCurve): LedTransfer = if (curve == TransferCurve.Identity) Identity else Curved(curve)
    }
}
