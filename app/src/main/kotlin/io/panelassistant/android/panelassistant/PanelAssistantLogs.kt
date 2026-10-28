package io.panelassistant.android.panelassistant

import io.panelassistant.android.Config
import io.panelassistant.android.logship.LogCapture
import io.panelassistant.android.logship.LogCaptureRecord
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

/** Session-owned log subscription. The transport loop is the sole sender and acknowledgement owner. */
internal class PanelAssistantLogs(
    private val captures: Map<String, LogCapture>,
    private val forwarding: (Boolean) -> Unit,
    private val savedSink: () -> JSONObject,
    private val capacity: Int = 1_000,
) {
    constructor(config: Config, app: LogCapture, system: LogCapture, forwarding: (Boolean) -> Unit) : this(
        mapOf("app" to app, "system" to system), forwarding, {
            JSONObject().put("enabled", config.logShipEnabled).put("system_enabled", config.logShipSystemEnabled)
                .put("host", config.logShipHost).put("port", config.logShipPort)
                .put("protocol", when (config.logShipProtocol) {
                    "syslog-udp" -> "udp"
                    "http" -> "http"
                    else -> "tcp"
                })
        },
    )

    private data class Entry(val source: String, val priority: Int, val json: JSONObject, val bytes: Int)
    private data class Pending(val id: Long, val records: Int, val deadline: Long)
    private val queue = ArrayDeque<Entry>()
    private var bytes = 0
    private var dropped = 0
    private var pending: Pending? = null
    private var generation: Any? = null
    private var subscriptions = emptyList<AutoCloseable>()
    val wake = Channel<Unit>(Channel.CONFLATED)

    init { require(capacity > 0) }

    fun hello(): JSONObject = JSONObject().put("saved_sink", savedSink())

    @Synchronized fun open(grant: Map<String, Int>) {
        disconnect()
        if (grant.isEmpty()) {
            queue.clear()
            bytes = 0
            dropped = 0
            return
        }
        // A replacement policy cannot send records admitted by a broader previous grant.
        val retained = queue.filter { it.priority >= (grant[it.source] ?: Int.MAX_VALUE) }
        queue.clear()
        queue.addAll(retained)
        bytes = retained.sumOf { it.bytes }
        val current = Any()
        generation = current
        forwarding(true)
        subscriptions = grant.mapNotNull { (source, minimum) ->
            captures[source]?.subscribeRecords { record -> enqueue(current, source, minimum, record) }
        }
        wake.trySend(Unit)
    }

    @Synchronized fun disconnect() {
        val active = generation != null
        generation = null
        subscriptions.forEach { it.close() }
        subscriptions = emptyList()
        pending?.let { drop(it.records) }
        pending = null
        if (active) forwarding(false)
    }

    @Synchronized private fun enqueue(current: Any, source: String, minimum: Int, record: LogCaptureRecord) {
        if (generation !== current || record.priority < minimum) return
        if (record.pid < 0 || record.priority !in 2..7 || record.tag.length > 128 ||
            record.message.toByteArray(Charsets.UTF_8).size > 8_192) {
            drop(1)
        } else {
            val json = JSONObject().put("timestamp", record.timestamp).put("source", source)
                .put("priority", record.priority).put("tag", record.tag).put("pid", record.pid)
                .put("message", record.message)
            val entry = Entry(source, record.priority, json, json.toString().toByteArray(Charsets.UTF_8).size)
            while (queue.isNotEmpty() && (queue.size >= capacity || bytes + entry.bytes > QUEUE_BYTES)) {
                bytes -= queue.removeFirst().bytes
                drop(1)
            }
            queue.addLast(entry)
            bytes += entry.bytes
        }
        wake.trySend(Unit)
    }

    /** Taking a batch transfers it out of the queue; a failed/ambiguous request is never replayed. */
    @Synchronized fun next(id: Long, now: Long, session: String): JSONObject? {
        if (generation == null || pending != null || (queue.isEmpty() && dropped == 0)) return null
        val records = JSONArray()
        var batchBytes = 2
        while (queue.isNotEmpty() && records.length() < 200) {
            val entry = queue.first()
            if (batchBytes + entry.bytes + 1 > BATCH_BYTES) break
            queue.removeFirst()
            bytes -= entry.bytes
            records.put(entry.json)
            batchBytes += entry.bytes + 1
        }
        val frame = JSONObject().put("id", id).put("type", "panel_assistant/report_log")
            .put("session", session).put("records", records).put("dropped", dropped)
        dropped = 0
        pending = Pending(id, records.length(), now + ACK_TIMEOUT_MS)
        return frame
    }

    @Synchronized fun result(id: Long, delivered: Boolean = true): Boolean {
        val request = pending?.takeIf { it.id == id } ?: return false
        if (!delivered) drop(request.records)
        pending = null
        wake.trySend(Unit)
        return true
    }

    @Synchronized fun deadline(): Long? = pending?.deadline
    @Synchronized fun expire(now: Long): Boolean = pending?.let { now >= it.deadline } ?: false

    private fun drop(count: Int) { dropped = (dropped.toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    private companion object {
        const val QUEUE_BYTES = 1_048_576
        const val BATCH_BYTES = 262_144
        const val ACK_TIMEOUT_MS = 15_000L
    }
}
