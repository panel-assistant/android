package io.panelassistant.android.mqtt

import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Latest-generation coalescing for hardware command read-backs (Issue #93).
 *
 * Every relay/button-LED command ends in a physical read-back before its state is republished, but on
 * a serialized root lane a burst must not queue one privileged read per command: only the newest
 * command's read-back can still describe the channel. Each command bumps its channel's generation
 * BEFORE the hardware write, so a read-back scheduled by an older command — including one already
 * executing while the newer write is in progress — observes that it is superseded and yields. The
 * newest generation always performs a fresh physical read via [readback], so verification is never
 * weakened, only deduplicated; a superseded read-back is counted as coalesced, never published.
 *
 * The generation check is not one moment but the whole interval: the scheduled task pre-checks before
 * touching hardware, and the SAME predicate travels into [readback] so the publisher can re-evaluate
 * it at admission time, under its own lock — a command arriving mid-observation withdraws the stale
 * publication rather than being trailed by it. The newer command's read-back is ordered behind the
 * stale one on the pump, so a withdrawal never leaves the channel unpublished.
 *
 * [schedule] must execute every task, in submission order, on one worker. It must NOT drop tasks on
 * lifecycle retirement — the feature-cost span opened at scheduling time is closed inside the task,
 * and a dropped task would leave the process-global in-flight count elevated forever. Cancellation
 * belongs inside [readback] (the converger's own lifecycle gate no-ops after close), never to the
 * scheduler.
 */
class CommandReadbackGate(
    private val schedule: (() -> Unit) -> Unit,
    private val readback: (key: String, stillCurrent: () -> Boolean) -> Unit,
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
) {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Execute [write] for the newest desired state of [key], then schedule its generation-guarded
     * read-back. The read-back is scheduled even when the write fails or throws — publishing the
     * physical state after a failed command is exactly what keeps HA honest.
     */
    fun command(key: String, write: () -> Boolean): Boolean {
        val generation = generations.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        try {
            return write()
        } finally {
            val cost = featureCosts.span(FeatureCostOperation.RELAY_READBACK)
            val stillCurrent = { generations.getValue(key).get() == generation }
            schedule {
                try {
                    if (stillCurrent()) {
                        readback(key, stillCurrent)
                    } else {
                        featureCosts.recordCoalesced(FeatureCostOperation.RELAY_READBACK)
                        cost.outcome(FeatureCostOutcome.CANCELLED)
                    }
                } finally {
                    cost.close()
                }
            }
        }
    }
}
