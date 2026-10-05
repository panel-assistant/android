package io.panelassistant.android.util

import java.util.concurrent.CompletableFuture

/**
 * Process-local ordering between Android Service instances.
 *
 * Android may recreate a START_STICKY service in the same process before the predecessor's asynchronous
 * teardown has finished. The first service opens immediately; every later instance remains fenced until
 * its predecessor proves complete. An explicit or incomplete restart still exits the process, but an
 * ordinary clean stop releases its successor without killing unrelated application components.
 */
internal class ServiceRestartBarrier {
    private val lock = Any()
    private var tail = CompletableFuture.completedFuture(Unit)

    fun enter(): Lease = synchronized(lock) {
        val predecessor = tail
        val completion = CompletableFuture<Unit>()
        tail = completion
        Lease(predecessor, completion)
    }

    internal class Lease(
        private val predecessor: CompletableFuture<Unit>,
        private val completion: CompletableFuture<Unit>,
    ) {
        fun awaitPredecessor() {
            predecessor.get()
        }

        /** Open the next same-process generation only after this generation is completely quiescent. */
        fun completeTeardown() {
            completion.complete(Unit)
        }
    }
}
