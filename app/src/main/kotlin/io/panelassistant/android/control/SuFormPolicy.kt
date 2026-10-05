package io.panelassistant.android.control

import io.panelassistant.android.util.MonotonicDeadline
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cached `su` dialect selection. A successful dialect is sticky because trying the other syntax after
 * a command-specific non-zero exit would misclassify the command result. A prior negative probe is not
 * sticky: root-manager readiness can change after boot, so every later one-shot boundary may try both.
 */
internal object SuFormPolicy {
    const val UNPROBED = -1
    const val TOOLBOX = 0
    const val ANDROID = 1
    const val NONE_LAST_PROBE = 2

    fun candidates(cached: Int): IntArray = when (cached) {
        TOOLBOX, ANDROID -> intArrayOf(cached)
        else -> intArrayOf(TOOLBOX, ANDROID)
    }

    fun working(cached: Int): Boolean = cached == TOOLBOX || cached == ANDROID

    data class Selection<T : Any>(val form: Int, val value: T)

    /**
     * Try each candidate dialect for [cached] in order until [attempt] returns a non-null value, and
     * return the winning form paired with that value; null when every candidate returned null.
     * "Accepted", not "succeeded": the caller decides what a non-null attempt means — the streamed-stdin
     * path intentionally accepts a completed process even on a non-zero exit as proof the dialect
     * launched. Each attempt owns its own timeout — unlike [firstSuccessfulWithin], which slices one
     * shared deadline across the dialects. This is the sticky-form selection the one-shot entry points
     * share.
     */
    fun <T : Any> firstAccepted(cached: Int, attempt: (form: Int) -> T?): Selection<T>? {
        for (candidate in candidates(cached)) {
            val value = attempt(candidate) ?: continue
            return Selection(candidate, value)
        }
        return null
    }

    /** Try dialects under one caller-owned deadline so a retry cannot multiply the public timeout. */
    fun <T : Any> firstSuccessfulWithin(
        cached: Int,
        deadline: MonotonicDeadline,
        attempt: (form: Int, deadline: MonotonicDeadline) -> T?,
    ): Selection<T>? {
        val candidates = candidates(cached)
        for ((index, candidate) in candidates.withIndex()) {
            val remainingMs = deadline.remainingMs()
            if (remainingMs <= 0L) break
            val candidatesLeft = candidates.size - index
            val sliceMs = remainingMs / candidatesLeft + if (remainingMs % candidatesLeft == 0L) 0L else 1L
            val value = attempt(candidate, deadline.cappedTo(sliceMs)) ?: continue
            return Selection(candidate, value)
        }
        return null
    }
}

/**
 * The shared, mutable `su` dialect selection state for [Su]: one owner for the sticky working form and
 * the negative-probe degrade so the deliberately unsynchronized one-shot entry points agree on it.
 * Reads are lock-free ([AtomicInteger]); [recordExhaustion] degrades a never-proven form **atomically**
 * so it can never overwrite a concurrent [recordSuccess], and leaves an already-working or already-
 * negative form untouched (matching the pre-consolidation `if (form == UNPROBED)` guard).
 */
internal class SuFormState(initial: Int = SuFormPolicy.UNPROBED) {
    private val form = AtomicInteger(initial)

    fun current(): Int = form.get()
    fun candidates(): IntArray = SuFormPolicy.candidates(form.get())
    fun working(): Boolean = SuFormPolicy.working(form.get())

    /** A dialect launched and its attempt was accepted: it becomes the sticky form (authoritative). */
    fun recordSuccess(f: Int) {
        form.set(f)
    }

    /** Every dialect failed: degrade a never-proven form to the negative probe, atomically, so a
     *  concurrent [recordSuccess] is never clobbered; a working/negative form is left as-is. */
    fun recordExhaustion() {
        form.compareAndSet(SuFormPolicy.UNPROBED, SuFormPolicy.NONE_LAST_PROBE)
    }
}
