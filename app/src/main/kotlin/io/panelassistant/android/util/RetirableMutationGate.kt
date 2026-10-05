package io.panelassistant.android.util

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * One serial mutation lane with a terminal admission fence.
 *
 * Actions run outside the admission state, so closing never waits to mark the owner terminal. A caller
 * can then spend its existing deadline proving the active mutation exited. Operations which must remain
 * restartable use [runExclusive] without closing admission.
 */
internal class RetirableMutationGate {
    private val open = AtomicBoolean(true)
    private val mutation = ReentrantLock(true)

    fun isOpen(): Boolean = open.get()

    fun closeAdmission() {
        open.set(false)
    }

    fun <T> runIfOpen(rejected: T, action: () -> T): T {
        if (!open.get()) return rejected
        try {
            mutation.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return rejected
        }
        return try {
            if (open.get()) action() else rejected
        } finally {
            mutation.unlock()
        }
    }

    fun <T> runExclusive(action: () -> T): T {
        mutation.lock()
        return try {
            action()
        } finally {
            mutation.unlock()
        }
    }

    fun awaitDrained(deadline: MonotonicDeadline): Boolean {
        if (mutation.isHeldByCurrentThread) return false
        val acquired = try {
            val remainingMs = deadline.remainingMs()
            if (remainingMs <= 0L) mutation.tryLock()
            else mutation.tryLock(remainingMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (acquired) mutation.unlock()
        return acquired
    }
}
