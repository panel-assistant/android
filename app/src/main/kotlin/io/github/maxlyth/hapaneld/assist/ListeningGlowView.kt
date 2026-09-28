package io.github.maxlyth.hapaneld.assist

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
        val depth = minOf(w, h) * DEPTH_FRACTION
        val edge = Color.argb(EDGE_ALPHA, Color.red(color), Color.green(color), Color.blue(color))
        val clear = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        fun band(x0: Float, y0: Float, x1: Float, y1: Float, left: Float, top: Float, right: Float, bottom: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, edge, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(left, top, right, bottom, paint)
        }
        band(0f, 0f, 0f, depth, 0f, 0f, w, depth)
        band(0f, h, 0f, h - depth, 0f, h - depth, w, h)
        band(0f, 0f, depth, 0f, 0f, 0f, depth, h)
        band(w, 0f, w - depth, 0f, w - depth, 0f, w, h)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEPTH_FRACTION = 0.14f
        const val EDGE_ALPHA = 150
    }
}
