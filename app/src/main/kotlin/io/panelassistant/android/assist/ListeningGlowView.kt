package io.panelassistant.android.assist

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * A soft tint drawn in from every edge of the dashboard for as long as the voice assistant is listening,
 * thinking or answering, so the room can see the panel is still attending after the wake ripple has gone.
 * It never takes a touch.
 */
class ListeningGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var color = Color.parseColor("#00FF88")
    private var animator: ValueAnimator? = null
    private var pulse: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        alpha = 0f
        // The outlines are drawn once into a layer; the fade and the pulse only change the layer's
        // opacity, so neither repaints the tint on the panel's slow CPU.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Tint in [value], the colour of the pipeline that is listening. */
    fun setColor(value: Int) {
        if (value == color) return
        color = value
        invalidate()
    }

    /** Fade the tint in or out. */
    fun setListening(active: Boolean) {
        animator?.cancel()
        pulse?.cancel()
        pulse = null
        if (active) visibility = VISIBLE
        animator = ValueAnimator.ofFloat(alpha, if (active) 1f else 0f).apply {
            duration = if (active) 250L else 400L
            addUpdateListener { alpha = it.animatedValue as Float }
            if (active) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animation === animator) breathe()
                }
            })
            if (!active) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (this@ListeningGlowView.alpha == 0f) visibility = GONE
                }
            })
            start()
        }
    }

    /** A slow swell and ebb while the assistant attends, so the room can see it is still active. */
    private fun breathe() {
        pulse = ValueAnimator.ofFloat(1f, PULSE_LOW).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Stacked frames, each filled from the screen edge in to a rounded inner edge, so the tint reaches
        // right into the corners and only its fading inner edge curves. Each frame's opacity is chosen so
        // the stack builds the falloff, strongest at the edge.
        val depth = minOf(w, h) * DEPTH_FRACTION
        val step = depth / STEPS
        val radius = depth * CORNER_FACTOR
        paint.style = Paint.Style.FILL
        for (i in 0 until STEPS) {
            val inset = step * (i + 1)
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            path.addRoundRect(inset, inset, w - inset, h - inset, radius, radius, Path.Direction.CW)
            paint.color = Color.argb(LAYER_ALPHA[i], Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(path, paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        pulse?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        // Strong enough to read over a light dashboard, where a paler tint disappears into the white;
        // the pulse ebbs to 60 % so it never fades out between breaths.
        const val DEPTH_FRACTION = 0.14f
        const val EDGE_ALPHA = 230
        const val STEPS = 24
        /** The inner edge's corner radius, as a share of the tint's depth: a soft corner, not a curve. */
        const val CORNER_FACTOR = 0.4f
        const val PULSE_LOW = 0.6f
        const val PULSE_MS = 900L

        /**
         * Frame i covers every band from the edge to band i, so band j shows every frame from j inward.
         * Solving for each frame's opacity gives band j a total of EDGE_ALPHA times (1 - j / STEPS) squared.
         */
        val LAYER_ALPHA = IntArray(STEPS) { i ->
            fun target(band: Int): Float {
                val fade = 1f - band.toFloat() / STEPS
                return EDGE_ALPHA / 255f * fade * fade
            }
            ((1f - (1f - target(i)) / (1f - target(i + 1))) * 255f).toInt().coerceIn(0, 255)
        }
    }
}
