package io.panelassistant.android.panelassistant

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.BuildConfig
import java.util.concurrent.atomic.AtomicLong

/** Observations only: no persistent state, lease authority, or session credentials. */
internal class LocalAdbLeaseDiagnostics(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val observer: (String) -> Unit = { Log.i("AdbLease", it); Unit },
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val started = now()
    private val startedElapsed = elapsedNow()
    private val runtimeId = runtimeIds.incrementAndGet()
    private val processStart = runCatching { Process.getStartElapsedRealtime() }.getOrDefault(startedElapsed)
    private val processId = "${runCatching { Process.myPid() }.getOrDefault(0)}-$processStart"
    private var sequence = 0L
    private var lastBeat: Long? = null
    private var lastRenewal: Long? = null
    private var armed = false
    private var unhealthySinceRenewal = false
    private var arms = 0L
    private var beats = 0L
    private val skips = linkedMapOf("unhealthy" to 0L, "state_unavailable" to 0L, "beat_false" to 0L, "arm_false" to 0L, "socket_error" to 0L)
    private val beatGaps = Histogram()
    private val renewalGaps = Histogram()
    private val healthyRenewalGaps = Histogram()
    private val lockWaits = Histogram()

    // uptimeMillis and the recovery owner's CLOCK_MONOTONIC both exclude deep sleep.
    fun now(): Long = runCatching(clock).getOrDefault(0L)
    private fun elapsedNow(): Long = runCatching(elapsedClock).getOrDefault(0L)

    // A broken clock or logging sink must never alter ARM/BEAT, publishing, or cancellation behavior.
    private inline fun observe(action: () -> Unit) { try { synchronized(this) { action() } } catch (_: Throwable) {} }

    fun arm() = observe {
        val at = now()
        val gap = lastRenewal?.let { (at - it).coerceAtLeast(0) } ?: -1L
        arms++
        recordRenewalGap(gap)
        emit("arm", at, "gap_ms=-1 renewal_gap_ms=$gap unhealthy_since_renewal=$unhealthySinceRenewal first_arm=${!armed} startup_to_arm_ms=${if (!armed) (at - started).coerceAtLeast(0) else -1} startup_to_arm_elapsed_ms=${if (!armed) (elapsedNow() - startedElapsed).coerceAtLeast(0) else -1}")
        armed = true
        // Rearming censors a BEAT-to-BEAT interval across an open/replaced owner. The next BEAT's
        // renewal gap still includes the entire interval since this successful ARM.
        lastBeat = null
        lastRenewal = at
        unhealthySinceRenewal = false
    }

    fun beat() = observe {
        val at = now()
        val beatGap = lastBeat?.let { (at - it).coerceAtLeast(0) } ?: -1L
        val renewalGap = lastRenewal?.let { (at - it).coerceAtLeast(0) } ?: -1L
        beats++
        if (beatGap >= 0) beatGaps.add(beatGap)
        recordRenewalGap(renewalGap)
        emit("beat", at, "gap_ms=$beatGap renewal_gap_ms=$renewalGap unhealthy_since_renewal=$unhealthySinceRenewal")
        lastBeat = at
        lastRenewal = at
        unhealthySinceRenewal = false
    }

    private fun recordRenewalGap(gap: Long) {
        if (gap >= 0) {
            renewalGaps.add(gap)
            if (!unhealthySinceRenewal) healthyRenewalGaps.add(gap)
        }
    }

    fun skip(reason: String, operation: String = "tick") = observe {
        if (reason == "unhealthy") unhealthySinceRenewal = true
        if (reason in skips) skips[reason] = skips.getValue(reason) + 1
        emit("skip", now(), "reason=$reason operation=$operation unhealthy_since_renewal=$unhealthySinceRenewal")
    }

    fun lockWait(since: Long) = observe {
        val at = now()
        val wait = (at - since).coerceAtLeast(0)
        // Waiting for effects delays this tick; it does not skip it or change the healthy gate.
        if (wait >= 1_000L) {
            lockWaits.add(wait)
            emit("lock_wait", at, "wait_ms=$wait")
        }
    }

    private fun emit(event: String, at: Long, fields: String) {
        val record = "adb_lease event=$event process_id=$processId runtime_id=$runtimeId app_build=${BuildConfig.VERSION_CODE} helper_build=${BuildConfig.HELPER_BUILD_ID} seq=${++sequence} gap_clock=uptime_monotonic uptime_ms=$at elapsed_ms=${elapsedNow()} process_age_ms=${(elapsedNow() - processStart).coerceAtLeast(0)} runtime_age_ms=${(at - started).coerceAtLeast(0)} $fields"
        // Advance baselines even if the sink refuses a record.
        try { observer(record) } catch (_: Throwable) {}
    }

    @Synchronized fun summary(): String = "adb_lease scope=process_local reset=process_or_runtime_restart process_id=$processId runtime_id=$runtimeId age_ms=${(now() - started).coerceAtLeast(0)} elapsed_age_ms=${(elapsedNow() - startedElapsed).coerceAtLeast(0)} gap_clock=uptime_monotonic arms=$arms beats=$beats " +
        skips.entries.joinToString(" ") { "skip_${it.key}=${it.value}" } + " " +
        listOf("beat_gap" to beatGaps, "renewal_gap" to renewalGaps, "healthy_renewal_gap" to healthyRenewalGaps, "lock_wait_ge1000" to lockWaits).joinToString(" ") { (name, values) -> values.summary(name) } +
        " percentiles=fixed_histogram_upper_bounds_ms beat_gap_rearm=censored"

    /** Cumulative fixed memory, including a two-week collection without a recent-sample bias. */
    private class Histogram {
        private val counts = LongArray(bounds.size)
        private var count = 0L
        private var max = 0L
        fun add(value: Long) {
            count++
            max = maxOf(max, value)
            val index = bounds.binarySearch(value).let { if (it >= 0) it else -it - 1 }
            counts[index]++
        }
        private fun percentile(perMille: Int): Long {
            if (count == 0L) return -1
            val rank = (count / 1000 * perMille) + ((count % 1000 * perMille + 999) / 1000)
            var seen = 0L
            for (i in counts.indices) {
                seen += counts[i]
                if (seen >= rank) return minOf(bounds[i], max)
            }
            return max
        }
        fun summary(name: String) = "${name}_count=$count ${name}_max_ms=${if (count == 0L) -1 else max} ${name}_p99_upper_ms=${percentile(990)} ${name}_p999_upper_ms=${percentile(999)}"
    }

    companion object {
        private val runtimeIds = AtomicLong()
        private val bounds = ((0L..60_000L step 1_000L) + (65_000L..300_000L step 5_000L) +
            (360_000L..3_600_000L step 60_000L) + (7_200_000L..172_800_000L step 3_600_000L) + Long.MAX_VALUE).toLongArray()
    }
}

/** Only the production factory installs a source; constructing test runtimes cannot replace it. */
internal object LocalAdbLeaseDiagnosticHolder {
    @Volatile private var source: LocalAdbLeaseDiagnostics? = null
    fun install(source: LocalAdbLeaseDiagnostics) { this.source = source }
    fun summary(): String = runCatching { source?.summary() }.getOrNull() ?: "adb_lease scope=process_local state=not_started"
}
