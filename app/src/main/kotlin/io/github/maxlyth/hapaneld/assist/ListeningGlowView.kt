package io.github.maxlyth.hapaneld.assist

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * A soft tint drawn in from every edge of the dashboard for as long as the voice assistant is listening,
 * thinking or answering, so the room can see the panel is still attending after the wake ripple has gone.
 * It never takes a touch.
 */
class ListeningGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Color.parseColor("#00FF88")
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        alpha = 0f
    }

    /** Fade the tint in or out. */
    fun setListening(active: Boolean) {
        animator?.cancel()
        if (active) visibility = VISIBLE
        animator = ValueAnimator.ofFloat(alpha, if (active) 1f else 0f).apply {
            duration = if (active) 250L else 400L
            addUpdateListener { alpha = it.animatedValue as Float }
            if (!active) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (this@ListeningGlowView.alpha == 0f) visibility = GONE
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Nested rounded outlines, strongest at the edge and fading inward, so the corners curve with the
        // tint instead of meeting at a mitred seam.
        val depth = minOf(w, h) * DEPTH_FRACTION
        val step = depth / STEPS
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = step + 1f
        for (i in 0 until STEPS) {
            val inset = step * i + step / 2
            val fade = 1f - i.toFloat() / STEPS
            paint.color = Color.argb((EDGE_ALPHA * fade * fade).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            val radius = depth + depth - inset
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEPTH_FRACTION = 0.14f
        const val EDGE_ALPHA = 150
        const val STEPS = 24
    }
}
