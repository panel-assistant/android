package io.panelassistant.android

import android.app.Activity
import android.os.Handler
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.panelassistant.android.util.LocalAdminReadiness
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The native full-screen Notice standing over the dashboard's WebView while the WebView has nothing of
 * Home Assistant's to show: the reconnecting state, or a sign-in the panel has stopped retrying. It is a
 * cover, not a replacement: the WebView keeps its document and every retry path behind it. The caller's
 * two state flags own its life; the caller hides it whenever neither holds.
 *
 * Its clock ticks only while the screen is lit: nobody can read it in the dark, so it stops there (even on a
 * cover first put up in the dark) and repaints from its unchanged origin as the screen wakes.
 */
internal class DashboardWebCover(
    private val activity: Activity,
    private val main: Handler,
    /** Whether the panel's screen is lit now. */
    private val screenAwake: () -> Boolean = { true },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    /** Where the clock's next tick is posted and cancelled: [main] on a panel. */
    private val post: (Runnable, Long) -> Unit = { tick, delayMs -> main.postDelayed(tick, delayMs) },
    private val cancel: (Runnable) -> Unit = { main.removeCallbacks(it) },
    /** Paint the clock into a hero still in its window; false once it has left. */
    private val paint: (TextView, String) -> Boolean = { view, text -> view.isAttachedToWindow.also { if (it) view.text = text } },
) {
    private var cover: StatusSurface? = null
    private var hero: TextView? = null
    private var sinceMs = 0L
    private val tick = object : Runnable {
        override fun run() {
            val shown = hero ?: return
            if (!paint(shown, haLifecycleClock(now() - sinceMs))) return
            if (screenAwake()) post(this, 1_000L)
        }
    }

    /**
     * Put up a fresh cover over [container], above the WebView and below the restart card and the chips,
     * which were added after it; [fill] draws its body.
     */
    fun show(container: ViewGroup?, dark: Boolean, fill: (StatusSurface) -> Unit) {
        container ?: return
        hide()
        val next = StatusSurface(activity, dark)
        // A status screen, not a see-through layer: touches stop here rather than reaching the page behind.
        next.root.isClickable = true
        fill(next)
        container.addView(next.root, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cover = next
    }

    /** Count [view] up from [fromMs] (elapsed-realtime) once a second while the cover shows it and the screen is lit. */
    fun countUp(view: TextView, fromMs: Long) {
        hero = view
        sinceMs = fromMs
        view.text = haLifecycleClock(now() - fromMs)
        if (screenAwake()) post(tick, 1_000L)
    }

    /** The screen went dark or lit: stop the clock, or repaint it at once and carry on counting. */
    fun screenChanged(awake: Boolean) {
        cancel(tick)
        if (awake) tick.run()
    }

    fun hide() {
        cancel(tick)
        cover?.root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cover = null
        hero = null
    }
}

/**
 * Whether the address of the panel's own sign-in setting may be offered for a phone: only when that page
 * was proven to answer at that address in the last minute (maintainer, 2026-10-08, ahead of partly
 * disabling direct panel access). When it is not known, one background request asks it, and the screen's
 * redraw runs once it answers, so a panel whose pages are not reachable from the network shows no code and
 * no address rather than one that leads nowhere.
 */
internal class SignInPageCheck(
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val answers: (String) -> Boolean = { LocalAdminReadiness.pageAnswers(it) },
) {
    private var last: Triple<String, Boolean, Long>? = null
    private var asking: String? = null

    /** [url] when it is proven to answer, else null; asks in the background and calls [redraw] on a yes. */
    fun proven(url: String?, redraw: () -> Unit): String? {
        url ?: return null
        last?.takeIf { it.first == url && now() - it.third < TRUST_MS }?.let { return url.takeIf { _ -> it.second } }
        if (asking != url) {
            asking = url
            scope.launch {
                val yes = withContext(io) { answers(url) }
                asking = null
                last = Triple(url, yes, now())
                if (yes) redraw()
            }
        }
        return null
    }

    companion object {
        /** How long a proven address is trusted before it is asked again. */
        const val TRUST_MS = 60_000L
    }
}
