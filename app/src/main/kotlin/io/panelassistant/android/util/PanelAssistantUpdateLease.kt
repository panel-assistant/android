package io.panelassistant.android.util

/**
 * Whether a Panel Assistant config entry currently owns this panel's ha-paneld update entity, in which
 * case the MQTT bridge withholds its own so a user of both never sees two.
 *
 * The integration declares ownership with [HEADER] on its 30-second status poll, only while its own
 * update entity is registered and enabled. Each observation renews a [LEASE_MS] lease. The lease is
 * persisted as a wall-clock time, written at most every [PERSIST_INTERVAL_MS], so a restarted panel
 * starts suppressed instead of announcing the entity before the first poll arrives. Within one process
 * the monotonic clock is authoritative; the persisted value only bridges a restart.
 *
 * Most panels boot without a real-time clock. A persisted time in the future therefore counts as
 * present only while uptime is under [LEASE_MS]: never expired by a wrong clock, never held forever.
 */
internal class PanelAssistantUpdateLease(
    private val wallNowMs: () -> Long,
    private val uptimeMs: () -> Long,
    private val readPersisted: () -> Long,
    private val writePersisted: (Long) -> Unit,
) {
    @Volatile private var observedUptimeMs: Long? = null

    /** Record one declaration. True when the lease was not active before it, so callers republish. */
    @Synchronized
    fun observe(): Boolean {
        val wasActive = active()
        val now = wallNowMs()
        observedUptimeMs = uptimeMs()
        val persisted = readPersisted()
        if (persisted <= 0L || kotlin.math.abs(now - persisted) >= PERSIST_INTERVAL_MS) writePersisted(now)
        return !wasActive
    }

    fun active(): Boolean {
        val uptime = uptimeMs()
        observedUptimeMs?.let { observed ->
            if (uptime - observed in 0 until LEASE_MS) return true
        }
        return persistedLeaseActive(readPersisted(), wallNowMs(), uptime)
    }

    companion object {
        const val HEADER = "X-Panel-Assistant-Update-Owner"
        const val HEADER_VALUE = "1"
        const val LEASE_MS = 24L * 60L * 60L * 1_000L
        const val PERSIST_INTERVAL_MS = 60L * 60L * 1_000L

        /** Exactly the agreed value; anything else is not a declaration. */
        fun declares(headerValue: String?): Boolean = headerValue == HEADER_VALUE

        internal fun persistedLeaseActive(seenWallMs: Long, nowWallMs: Long, uptimeMs: Long): Boolean {
            if (seenWallMs <= 0L) return false
            val age = nowWallMs - seenWallMs
            return if (age >= 0L) age < LEASE_MS else uptimeMs in 0 until LEASE_MS
        }
    }
}
