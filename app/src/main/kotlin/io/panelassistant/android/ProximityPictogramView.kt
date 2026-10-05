package io.panelassistant.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator

/** Native vector instructions: a person approaches and holds; a hand approaches the screen before an optional touch. */
internal class ProximityPictogramView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var cue = ProximityWizardCue.NONE
    private var hand = false
    private var waveCount = 1
    private var holdNear = false
    private var foreground = Color.WHITE
    private var accent = Color.CYAN
    private var phase = 0f
    private var presenting = false
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun present(
        nextCue: ProximityWizardCue,
        usesHand: Boolean,
        bodyColor: Int,
        accentColor: Int,
        requestedWaveCount: Int = 1,
        holdNearPanel: Boolean = false,
    ) {
        foreground = bodyColor
        accent = accentColor
        if (cue != nextCue || hand != usesHand || waveCount != requestedWaveCount || holdNear != holdNearPanel) {
            cue = nextCue
            hand = usesHand
            waveCount = requestedWaveCount.coerceIn(1, 2)
            holdNear = holdNearPanel
            phase = 0f
            restartAnimation()
        }
        invalidate()
    }

    fun setPresenting(value: Boolean) {
        presenting = value
        restartAnimation()
    }

    private fun restartAnimation() {
        animator?.cancel()
        animator = null
        val animationsEnabled = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }.getOrDefault(true)
        if (!presenting || !isAttachedToWindow || !animationsEnabled || cue == ProximityWizardCue.NONE) {
            phase = 0.5f
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (cue == ProximityWizardCue.WAVE && waveCount == 2) 1800L else 3200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 240f, height / 160f)
        canvas.save()
        canvas.translate((width - 240f * scale) / 2f, (height - 160f * scale) / 2f)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(64, 86, 113)
        canvas.drawLine(8f, 146f, 232f, 146f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(23, 43, 67)
        canvas.drawRoundRect(185f, 20f, 222f, 142f, 7f, 7f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = accent
        canvas.drawRoundRect(185f, 20f, 222f, 142f, 7f, 7f, paint)
        paint.color = Color.rgb(72, 124, 184)
        canvas.drawLine(191f, 49f, 216f, 49f, paint)
        canvas.drawLine(191f, 120f, 216f, 120f, paint)
        paint.color = accent
        canvas.drawCircle(203f, 34f, 3f, paint)
        // A double gesture is two short excursions followed by a clear pause, not continuous waving.
        val wavePhase = if (waveCount == 2) {
            when {
                phase < 0.3f -> phase / 0.3f
                phase < 0.6f -> (phase - 0.3f) / 0.3f
                else -> 0f
            }
        } else phase
        val movement = when (cue) {
            ProximityWizardCue.APPROACH -> (phase / 0.6f).coerceAtMost(1f)
            ProximityWizardCue.MOVE_AWAY -> 1f - (phase / 0.6f).coerceAtMost(1f)
            ProximityWizardCue.WAVE -> if (waveCount == 2) {
                if (wavePhase < 0.5f) wavePhase * 2f else (1f - wavePhase) * 2f
            } else {
                // One inward approach, a near hold, then an invisible reset. The separate
                // move-away cue teaches rearming without making withdrawal part of waking.
                val approach = (phase / 0.22f).coerceAtMost(1f)
                1f - (1f - approach) * (1f - approach)
            }
            ProximityWizardCue.HOLD -> if (holdNear) 1f else 0f
            else -> 0f
        }
        val x = 50f + movement * 72f
        paint.strokeWidth = 5f
        paint.color = foreground
        if (hand) {
            val alpha = if (cue == ProximityWizardCue.WAVE && waveCount == 1) {
                when {
                    phase < 0.06f -> phase / 0.06f
                    phase < 0.70f -> 1f
                    phase < 0.82f -> 1f - (phase - 0.70f) / 0.12f
                    else -> 0f
                }
            } else 1f
            drawHand(canvas, x, 82f, (alpha * 255).toInt())
        } else drawPerson(canvas, x)
        when (cue) {
            ProximityWizardCue.APPROACH -> arrow(canvas, 60f, 164f, 151f)
            ProximityWizardCue.MOVE_AWAY -> arrow(canvas, 164f, 60f, 151f)
            ProximityWizardCue.WAVE -> {
                arrow(canvas, 58f, 164f, 151f)
                if (waveCount == 2) arrow(canvas, 164f, 58f, 10f)
            }
            ProximityWizardCue.HOLD -> {
                paint.color = accent
                canvas.drawLine(150f, 64f, 150f, 89f, paint)
                canvas.drawLine(163f, 64f, 163f, 89f, paint)
            }
            else -> Unit
        }
        canvas.restore()
    }

    private fun drawPerson(canvas: Canvas, x: Float) {
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, 32f, 13f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawLine(x, 48f, x, 98f, paint)
        canvas.drawLine(x, 59f, x - 20f, 82f, paint)
        canvas.drawLine(x, 59f, x + 20f, 82f, paint)
        canvas.drawLine(x, 98f, x - 18f, 132f, paint)
        canvas.drawLine(x, 98f, x + 18f, 132f, paint)
    }

    private fun drawHand(canvas: Canvas, x: Float, y: Float, opacity: Int) {
        val outline = Path().apply {
            moveTo(x - 12f, y + 42f)
            lineTo(x - 12f, y + 23f)
            lineTo(x - 32f, y + 1f)
            quadTo(x - 37f, y - 8f, x - 28f, y - 9f)
            lineTo(x - 16f, y + 2f)
            lineTo(x - 16f, y - 34f)
            quadTo(x - 16f, y - 44f, x - 7f, y - 40f)
            lineTo(x - 6f, y - 13f)
            lineTo(x - 5f, y - 48f)
            quadTo(x - 3f, y - 56f, x + 5f, y - 48f)
            lineTo(x + 5f, y - 13f)
            lineTo(x + 8f, y - 40f)
            quadTo(x + 12f, y - 48f, x + 18f, y - 39f)
            lineTo(x + 17f, y - 10f)
            lineTo(x + 21f, y - 29f)
            quadTo(x + 28f, y - 35f, x + 30f, y - 25f)
            lineTo(x + 27f, y + 12f)
            quadTo(x + 27f, y + 25f, x + 17f, y + 29f)
            lineTo(x + 17f, y + 42f)
        }
        outline.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(opacity, 197, 222, 254)
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = foreground
        paint.alpha = opacity
        canvas.drawPath(outline, paint)
        paint.alpha = 255
    }

    private fun arrow(canvas: Canvas, from: Float, to: Float, y: Float) {
        paint.color = accent
        paint.strokeWidth = 4f
        canvas.drawLine(from, y, to, y, paint)
        val direction = if (to > from) 1f else -1f
        canvas.drawLine(to, y, to - direction * 9f, y - 6f, paint)
        canvas.drawLine(to, y, to - direction * 9f, y + 6f, paint)
        paint.strokeWidth = 5f
    }
}

/** A countdown arc uses the engine's remaining time; it never advances the calibration itself. */
internal class ProximityCountdownView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var fraction = 0f
    private var targetFraction = 0f
    private var accent = Color.rgb(113, 173, 255)
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun present(remainingMs: Long, durationMs: Long, color: Int) {
        accent = color
        val next = if (durationMs > 0) (remainingMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (next != targetFraction) {
            targetFraction = next
            animator?.cancel()
            val animate = runCatching {
                Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }.getOrDefault(true)
            if (!animate || next > fraction || !isAttachedToWindow) {
                fraction = next
            } else {
                animator = ValueAnimator.ofFloat(fraction, next).apply {
                    duration = 200
                    interpolator = LinearInterpolator()
                    addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 6f * resources.displayMetrics.density
        val diameter = minOf(width, height).toFloat() - stroke
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        paint.strokeWidth = stroke
        paint.color = Color.rgb(39, 59, 84)
        canvas.drawOval(left, top, left + diameter, top + diameter, paint)
        paint.color = accent
        canvas.drawArc(left, top, left + diameter, top + diameter, -90f, 360f * fraction, false, paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
