package io.panelassistant.android.input

/**
 * Bridges process-instantiated input sources to the current MQTT bridge. A subscription owns exactly
 * the listener it installed: closing an old bridge's subscription cannot clear a replacement bridge's
 * listener after a service reconfiguration.
 */
object ButtonBus {
    private data class Listener(val id: Long, val emit: (String) -> Unit)

    class Subscription internal constructor(private val id: Long) : AutoCloseable {
        override fun close() = unsubscribe(id)
    }

    private val lock = Any()
    private var nextId = 0L
    private var listener: Listener? = null

    /** Replace the process's event consumer and return an ownership token for only this registration. */
    fun subscribe(emit: (event: String) -> Unit): Subscription = synchronized(lock) {
        val id = ++nextId
        listener = Listener(id, emit)
        Subscription(id)
    }

    fun emit(event: String) {
        val target = synchronized(lock) { listener?.emit }
        target?.invoke(event)
    }

    private fun unsubscribe(id: Long) = synchronized(lock) {
        if (listener?.id == id) listener = null
    }
}
