package io.panelassistant.android.http

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps at most one completed backup artifact/download alive without blocking unrelated mutations. */
internal object BackupDeliveryGate {
    private val active = AtomicBoolean(false)

    fun acquire(): Lease? = if (active.compareAndSet(false, true)) Lease() else null
    fun occupied(): Boolean = active.get()

    class Lease internal constructor() : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) active.set(false)
        }
    }
}
