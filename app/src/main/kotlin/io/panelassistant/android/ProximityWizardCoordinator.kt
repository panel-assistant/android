package io.panelassistant.android

import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the visible calibration lifetime independently of the dashboard and browser renderers. */
internal class ProximityWizardCoordinator(
    private val startSession: () -> Boolean,
    private val active: () -> Boolean,
    private val status: () -> String,
    private val visible: () -> Boolean,
    private val localAction: (String) -> Boolean,
    private val cancel: (String?, String) -> Boolean,
    private val heartbeat: (String) -> Boolean,
    private val reset: () -> Boolean,
    private val acquireDisplay: (Any) -> Boolean,
    private val releaseDisplay: (Any) -> Unit,
    private val launch: () -> Boolean,
    private val narrator: ProximityWizardNarrator? = null,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val pendingAction = AtomicBoolean(false)
    private val displayOwner = Any()
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "proximity-wizard").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var watcher: ScheduledFuture<*>? = null

    init {
        ProximityWizardHost.attach(
            this,
            status,
            ::onPanelAction,
            narrate = { prompt, text, localeTag -> narrator?.narrate(prompt, text, localeTag) == true },
            stopNarration = { narrator?.stop() },
        )
    }

    /** Called by the same-origin UI route on an IO dispatcher. */
    @Synchronized
    fun remote(action: String, sessionId: String): Boolean {
        if (stopped.get()) return false
        return when (action) {
            "start" -> {
                if (active() || !acquireDisplay(displayOwner)) return false
                if (!startSession()) {
                    releaseDisplay(displayOwner)
                    return false
                }
                if (!runCatching(launch).getOrDefault(false)) {
                    cancel(null, "Could not show setup on the panel. Your previous calibration is unchanged.")
                    releaseDisplay(displayOwner)
                    return false
                }
                watch()
                true
            }
            "heartbeat" -> heartbeat(sessionId)
            "cancel" -> {
                narrator?.stop()
                cancel(sessionId, "").also { if (!active()) releaseDisplay(displayOwner) }
            }
            "reset" -> !active() && reset()
            else -> false
        }
    }

    /** Main-thread bridge: visibility is in-memory; all other work is queued and bounded to one action. */
    private fun onPanelAction(action: String): Boolean {
        if (stopped.get()) return false
        if (action == "visible") return visible()
        if (action == "cancel") narrator?.stop()
        if (!pendingAction.compareAndSet(false, true)) return false
        val expectedSession = runCatching { JSONObject(status()).optString("sessionId") }.getOrDefault("")
        return runCatching {
            worker.execute {
                try {
                    if (!stopped.get() && expectedSession.isNotEmpty() &&
                        JSONObject(status()).optString("sessionId") == expectedSession) {
                        if (action != "retry" || acquireDisplay(displayOwner)) {
                            localAction(action)
                        }
                        synchronized(this) {
                            if (active()) watch() else releaseDisplay(displayOwner)
                        }
                    }
                } finally { pendingAction.set(false) }
            }
            true
        }.getOrElse { pendingAction.set(false); false }
    }

    @Synchronized
    private fun watch() {
        if (stopped.get() || watcher?.isDone == false) return
        watcher = worker.scheduleWithFixedDelay({
            synchronized(this) {
                if (!active()) {
                    narrator?.stop()
                    releaseDisplay(displayOwner)
                    watcher?.cancel(false)
                    watcher = null
                }
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        ProximityWizardHost.detach(this)
        narrator?.close()
        worker.shutdownNow()
        // The service's screen admission closes independently; cancelling never writes calibration.
        releaseDisplay(displayOwner)
    }
}
