package io.panelassistant.android.control

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide, renderer-independent panel-touch observation.
 *
 * One 1 px `FLAG_WATCH_OUTSIDE_TOUCH` overlay is shared by every interested feature. Subscribers
 * receive only an event notification: coordinates, targets and gesture paths never leave the Android
 * callback. The overlay is non-focusable and non-touch-modal, and its listener always returns false, so
 * dashboard interaction remains authoritative.
 */
class PanelTouchObserver private constructor(context: Context) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val registry = TouchListenerRegistry()
    @Volatile private var view: View? = null

    interface Subscription : AutoCloseable {
        override fun close()
    }

    fun canObserve(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /**
     * Subscribe to one notification per `ACTION_OUTSIDE`. Returns only after the shared overlay is
     * confirmed attached, or null when permission/attachment is unavailable. Callbacks run on the main
     * looper and must remain non-blocking.
     */
    fun subscribe(onTouch: () -> Unit): Subscription? = subscribe(onTouch, requireOverlay = true)

    /** Register even without overlay permission so an Activity may feed [noteActivityTouch]. */
    fun subscribeWithActivityFallback(onTouch: () -> Unit): Subscription? =
        subscribe(onTouch, requireOverlay = false)

    /**
     * Attach a full-screen touch target before permitting darkness. Unlike ordinary observation this
     * owns the waking gesture, including when the renderer is in another app. Removing its window
     * cancels the input stream; Android never retargets the remaining MOVE/UP to the dashboard.
     */
    @Suppress("ClickableViewAccessibility")
    fun captureWakeTouches(onTouch: () -> Unit): Subscription? {
        if (!canObserve()) return null
        val active = AtomicBoolean(true)
        val candidate = arrayOfNulls<View>(1)
        val completed = onMain {
            if (!active.get()) return@onMain
            val target = View(ctx)
            target.setOnTouchListener { _, event ->
                if (active.get() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    registry.dispatch()
                    onTouch()
                }
                true
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            runCatching {
                wm.addView(target, params)
                candidate[0] = target
            }.onFailure {
                Log.w(TAG, "wake touch overlay addView failed: ${it.message}")
            }
        }
        if (!completed || candidate[0] == null) {
            active.set(false)
            // Runs after a timed-out attachment, and owns only this attempt's view.
            main.post { candidate[0]?.let { runCatching { wm.removeView(it) } } }
            return null
        }
        val target = candidate[0]!!
        return object : Subscription {
            override fun close() {
                if (!active.compareAndSet(true, false)) return
                main.post { runCatching { wm.removeView(target) } }
            }
        }
    }

    private fun subscribe(onTouch: () -> Unit, requireOverlay: Boolean): Subscription? {
        if (requireOverlay && !canObserve()) return null
        val active = AtomicBoolean(true)
        val result = arrayOfNulls<Subscription>(1)
        val completed = onMain {
            if (!active.get()) return@onMain
            val id = registry.add(active, onTouch)
            if (canObserve() && !ensureOverlay()) {
                registry.remove(id)
                return@onMain
            }
            if (requireOverlay && view == null) {
                registry.remove(id)
                return@onMain
            }
            if (!active.get()) {
                registry.remove(id)
                removeOverlayIfIdle()
                return@onMain
            }
            result[0] = ObserverSubscription(this, id, active)
        }
        if (!completed || result[0] == null) {
            active.set(false)
            main.post { removeInactiveAndOverlayIfIdle() }
            return null
        }
        return result[0]
    }

    /** Activity dispatch can use this when overlay permission is absent; it carries no event details. */
    fun noteActivityTouch() {
        if (view == null) registry.dispatch()
    }

    @Suppress("ClickableViewAccessibility")
    private fun ensureOverlay(): Boolean {
        if (view != null) return true
        val candidate = View(ctx)
        candidate.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) registry.dispatch()
            false
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        return runCatching {
            wm.addView(candidate, params)
            view = candidate
            true
        }.onFailure {
            Log.w(TAG, "shared touch overlay addView failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun unsubscribe(id: Long, active: AtomicBoolean) {
        if (!active.compareAndSet(true, false)) return
        main.post {
            registry.remove(id)
            removeOverlayIfIdle()
        }
    }

    private fun removeInactiveAndOverlayIfIdle() {
        registry.removeInactive()
        removeOverlayIfIdle()
    }

    private fun removeOverlayIfIdle() {
        if (!registry.isEmpty()) return
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun onMain(action: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return true
        }
        val done = CountDownLatch(1)
        if (!main.post {
                try {
                    action()
                } finally {
                    done.countDown()
                }
            }
        ) return false
        return try {
            done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    private class ObserverSubscription(
        private val owner: PanelTouchObserver,
        private val id: Long,
        private val active: AtomicBoolean,
    ) : Subscription {
        override fun close() = owner.unsubscribe(id, active)
    }

    companion object {
        private const val TAG = "ha-paneld/touch"
        private const val MAIN_TIMEOUT_MS = 1_000L

        @Volatile private var instance: PanelTouchObserver? = null

        fun shared(context: Context): PanelTouchObserver = instance ?: synchronized(this) {
            instance ?: PanelTouchObserver(context).also { instance = it }
        }
    }
}

/** Listener bookkeeping kept Android-free so fan-out and failure isolation are deterministic tests. */
internal class TouchListenerRegistry {
    private data class Listener(
        val id: Long,
        val callback: () -> Unit,
        val active: AtomicBoolean,
    )

    private var nextId = 1L
    private val listeners = LinkedHashMap<Long, Listener>()

    @Synchronized
    fun add(active: AtomicBoolean = AtomicBoolean(true), callback: () -> Unit): Long {
        val id = nextId++
        listeners[id] = Listener(id, callback, active)
        return id
    }

    @Synchronized
    fun remove(id: Long) {
        listeners.remove(id)
    }

    @Synchronized
    fun removeInactive() {
        listeners.entries.removeAll { !it.value.active.get() }
    }

    @Synchronized
    fun isEmpty(): Boolean = listeners.isEmpty()

    fun dispatch() {
        val snapshot = synchronized(this) { listeners.values.toList() }
        snapshot.filter { it.active.get() }.forEach { listener ->
            runCatching { listener.callback() }
                .onFailure { Log.w(TAG, "touch observer ${listener.id} failed: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "ha-paneld/touch"
    }
}
