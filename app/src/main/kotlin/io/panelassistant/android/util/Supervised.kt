package io.panelassistant.android.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Launch a supervised periodic task on this scope: after [initialDelayMs], run [body], wait [intervalMs],
 * repeat — until the scope (or the returned [Job]) is cancelled. Every iteration is wrapped so any
 * exception except cancellation goes to [onError] (default: logged) and the loop keeps ticking, so a
 * single failed tick can never silently kill a long-running reliability loop.
 *
 * Replaces the several hand-rolled `launch { delay(...); while (isActive) { runCatching { … }; delay(…) } }`
 * loops that each re-implemented (or forgot) the exception boundary and initial-settle delay differently.
 * For loops that must survive dispatcher starvation (the MQTT watchdog) keep the dedicated raw thread —
 * this is for the coroutine-scoped periodic tasks.
 */
fun CoroutineScope.periodic(
    intervalMs: Long,
    initialDelayMs: Long = 0L,
    tag: String = "ha-paneld/loop",
    name: String = "task",
    onError: (Throwable) -> Unit = { e -> Log.w(tag, "periodic '$name' iteration failed", e) },
    body: suspend () -> Unit,
): Job = launch {
    if (initialDelayMs > 0L) delay(initialDelayMs)
    while (isActive) {
        try {
            body()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            onError(e)
        }
        delay(intervalMs)
    }
}
