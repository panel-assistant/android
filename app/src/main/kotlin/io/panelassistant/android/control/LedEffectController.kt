package io.panelassistant.android.control

import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.LedEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one LED [LedEffects.Effect] as a cancellable background loop, driving [led] a frame at a time
 * (one [LedEffects.frame] per [LedEffects.Effect.stepMs] tick). Modelled on [WatchdogController]:
 * a single `@Volatile` job; [start] cancels-and-replaces, [stop] cancels.
 *
 * OWNED BY THE SERVICE, not the MQTT bridge: `PaneldService` holds the single instance and injects it
 * into every [io.panelassistant.android.MqttBridge] it builds. A bridge rebuild (panel_id / broker
 * change via `reconfigure()`) therefore cannot orphan a running loop — there is only ever ONE loop, and
 * it survives the rebuild (the new bridge re-issues [start] with the persisted colour, a seamless
 * cancel-and-replace). Its lifetime matches the LED hardware's, like [led] itself.
 *
 * Runs on [Dispatchers.IO] — mandatory, because the daemon-backed LED HAL
 * ([io.panelassistant.android.hardware.SocketLedController] via `HelperClient`) does blocking socket
 * I/O on every frame. [start]/[stop] are called from the MQTT callback thread (off-main) in `handleLed`.
 *
 * [start], [setSolid], [setOff], [stop], and [close] are `@Synchronized` and tear the previous job down
 * with **cancel-and-join**, so no in-flight frame can land after its replacement. [close] is terminal:
 * once service teardown begins, a late MQTT callback cannot restart an effect or write a solid value.
 *
 * [scope] is injectable purely so unit tests can drive the loop on a real test dispatcher; production
 * always uses the default IO scope. It must be a real dispatcher, not a virtual-time TestScope — the
 * cancel-and-join runs under [runBlocking] and would deadlock against virtual time.
 *
 * **Holds.** An indication that must not be overwritten — the camera-in-use light while the screen is
 * off — takes a [Hold]. While one is held, every ordinary write ([start], [setSolid], [setOff], [stop])
 * is refused and returns false, so the MQTT `handleLed` path persists the user's intent and reports the
 * actuation as unknown rather than silently painting over the indication. The holder writes through the
 * hold, and releasing it restores nothing: the release caller re-derives the LED from persisted intent,
 * which is the only source that also reflects a command that arrived mid-hold.
 */
class LedEffectController(
    private val led: LedController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var job: Job? = null
    private val runtime = AtomicReference(Runtime(0, Status.IDLE))
    @Volatile private var holder: Hold? = null

    enum class Status { IDLE, RUNNING, FAILED, CLOSED }

    /** An exclusive claim on the LED for an indication. Close it to give ordinary writers the LED back. */
    inner class Hold internal constructor() : AutoCloseable {
        /** Solid colour through the hold; refused once the hold is closed or the controller is closed. */
        fun setSolid(r: Int, g: Int, b: Int): Boolean = synchronized(this@LedEffectController) {
            if (holder !== this || status() == Status.CLOSED) return false
            cancelAndJoinCurrent(Status.IDLE)
            write(LedEffects.Frame.Rgb(r, g, b)).also { if (!it) setStatus(Status.FAILED) }
        }

        fun setOff(): Boolean = synchronized(this@LedEffectController) {
            if (holder !== this || status() == Status.CLOSED) return false
            cancelAndJoinCurrent(Status.IDLE)
            write(LedEffects.Frame.Off).also { if (!it) setStatus(Status.FAILED) }
        }

        val active: Boolean get() = holder === this

        override fun close() = synchronized(this@LedEffectController) {
            if (holder === this) holder = null
        }
    }

    /** Take the LED for an indication; null when closed or already held. Never restores on release. */
    @Synchronized
    fun hold(): Hold? {
        if (status() == Status.CLOSED || holder != null) return null
        return Hold().also { holder = it }
    }

    /** True while a [Hold] owns the LED and ordinary writes are being refused. */
    fun held(): Boolean = holder != null

    private data class Runtime(val generation: Long, val status: Status)

    /** Start or replace [effect]. The first frame is confirmed before success is returned. */
    @Synchronized
    fun start(effect: LedEffects.Effect, r: Int, g: Int, b: Int, br: Int): Boolean {
        if (status() == Status.CLOSED || holder != null) return false
        cancelAndJoinCurrent(Status.IDLE)
        if (!write(LedEffects.frame(effect, 0, r, g, b, br))) {
            setStatus(Status.FAILED)
            return false
        }
        val run = setStatus(Status.RUNNING)
        job = scope.launch {
            var step = 1
            try {
                while (isActive) {
                    delay(effect.stepMs)
                    if (!write(LedEffects.frame(effect, step++, r, g, b, br))) {
                        runtime.compareAndSet(run, Runtime(run.generation, Status.FAILED))
                        break
                    }
                }
            } finally {
                // An external scope cancellation is a failed owner unless stop/close/replacement already
                // advanced the generation. A normal owned cancellation therefore cannot overwrite state.
                runtime.compareAndSet(run, Runtime(run.generation, Status.FAILED))
            }
        }
        return true
    }

    /** Cancel any effect, then confirm one solid RGB write. */
    @Synchronized
    fun setSolid(r: Int, g: Int, b: Int): Boolean {
        if (status() == Status.CLOSED || holder != null) return false
        cancelAndJoinCurrent(Status.IDLE)
        return write(LedEffects.Frame.Rgb(r, g, b)).also { if (!it) setStatus(Status.FAILED) }
    }

    /** Cancel any effect, then confirm all channels off. */
    @Synchronized
    fun setOff(): Boolean {
        if (status() == Status.CLOSED || holder != null) return false
        cancelAndJoinCurrent(Status.IDLE)
        return write(LedEffects.Frame.Off).also { if (!it) setStatus(Status.FAILED) }
    }

    /**
     * Cancel any running effect and wait for its in-flight frame to finish. The caller then applies the
     * solid colour / off state itself, guaranteed to be the last write to [led].
     */
    @Synchronized
    fun stop() {
        if (status() != Status.CLOSED && holder == null) cancelAndJoinCurrent(Status.IDLE)
    }

    /** True while an effect loop is active. */
    fun running(): Boolean = status() == Status.RUNNING && job?.isActive == true

    fun status(): Status = runtime.get().status

    /** Terminally refuse new output and wait for the last owned frame. Idempotent. */
    @Synchronized
    fun close() {
        holder = null
        if (status() != Status.CLOSED) cancelAndJoinCurrent(Status.CLOSED)
    }

    /** Cancel the current job and block until its last frame has finished, so no stale frame survives. */
    private fun cancelAndJoinCurrent(next: Status) {
        setStatus(next)
        val j = job ?: return
        job = null
        runBlocking { j.cancelAndJoin() }
    }

    private fun setStatus(status: Status): Runtime {
        val next = Runtime(runtime.get().generation + 1, status)
        runtime.set(next)
        return next
    }

    private fun write(frame: LedEffects.Frame): Boolean = runCatching {
        when (frame) {
            is LedEffects.Frame.Rgb -> led.setRgb(frame.r, frame.g, frame.b)
            LedEffects.Frame.Off -> led.off()
        }
    }.onFailure { android.util.Log.w(TAG, "LED write failed", it) }.getOrDefault(false)

    private companion object {
        const val TAG = "ha-paneld/led-fx"
    }
}
