package io.panelassistant.android.util

/** No-queue admission token for work that must later execute on another owner (for example main looper). */
internal class GenerationSingleFlight {
    private var generation = 0L
    private var inFlight = false
    private var closed = false

    @Synchronized fun claim(): Long? {
        if (closed || inFlight) return null
        inFlight = true
        generation++
        return generation
    }

    @Synchronized fun isCurrent(token: Long): Boolean = !closed && inFlight && generation == token

    @Synchronized fun finish(token: Long) {
        if (generation == token) inFlight = false
    }

    @Synchronized fun close() {
        closed = true
        inFlight = false
        generation++
    }
}
