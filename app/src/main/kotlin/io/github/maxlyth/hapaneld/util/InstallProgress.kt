package io.github.maxlyth.hapaneld.util

import kotlinx.coroutines.Job

/**
 * Process-wide ownership and progress for destructive control-plane operations. HTTP, MQTT, and scheduled
 * callers all acquire the same single slot before installs, uninstall, repair, or restore; the web UI polls
 * GET /api/v1/install/status for the current owner and result.
 */
object InstallProgress {
    class Ticket internal constructor(internal val id: Long)
    class ConfigMutationTicket internal constructor(internal val id: Long)

    enum class Outcome(val wireValue: String) {
        SUCCEEDED("succeeded"),
        PARTIAL("partial"),
        FAILED("failed"),
        SKIPPED("skipped"),
        ROLLED_BACK("rolled_back"),
        ROLLBACK_FAILED("rollback_failed"),
    }

    /** Fixed-shape, bounded machine result for multi-component control-plane operations. Details are
     * deliberately short and optional: this status endpoint must never become a path for bundle data,
     * credentials, entity ids, or other request-controlled payloads. */
    data class ComponentResult(
        val status: Outcome,
        val items: Int? = null,
        val detail: String = "",
        val presentation: InstallPresentation? = null,
    )

    data class OperationResult(
        val status: Outcome,
        val config: ComponentResult? = null,
        val profiles: ComponentResult? = null,
        val companion: ComponentResult? = null,
        val rollback: ComponentResult? = null,
        /** Imported wake words a restore carried; absent when the backup held none. */
        val wakeWords: ComponentResult? = null,
    )

    data class PresentationSnapshot(
        val generation: Long,
        val running: Boolean,
        val component: String,
        val message: String,
        val presentation: InstallPresentation?,
    )

    @Volatile var running: Boolean = false; private set
    @Volatile var component: String = ""; private set
    @Volatile var message: String = ""; private set
    @Volatile private var presentation: InstallPresentation? = null
    @Volatile private var result: OperationResult? = null
    private var generation = 0L
    private var active: Ticket? = null
    private var activeConfigMutation: ConfigMutationTicket? = null

    /**
     * Told after every claim or release of the visible operation lane, outside this object's monitor.
     * The service republishes the MQTT update entities' progress from it; this object stays MQTT-free.
     */
    @Volatile var observer: (() -> Unit)? = null

    private fun notifyObserver() {
        observer?.let { runCatching { it() } }
    }

    /** Claim the operation lane for [component]. Returns null if another owner is already in flight. */
    fun start(component: String, presentation: InstallPresentation? = null): Ticket? =
        startLocked(component, presentation)?.also { notifyObserver() }

    @Synchronized
    private fun startLocked(component: String, presentation: InstallPresentation?): Ticket? {
        if (running || activeConfigMutation != null) return null
        val ticket = Ticket(++generation)
        active = ticket
        this.component = component
        this.message = "Working…"
        this.presentation = presentation
        this.result = null
        this.running = true
        return ticket
    }

    /**
     * Claim the same process-wide operation lane for an ordinary Configure mutation without exposing it
     * as an install/restore progress operation. A destructive operation already in flight must make the
     * save fail visibly rather than apply a value that the older operation then restores from its archive.
     */
    @Synchronized
    fun startConfigMutation(): ConfigMutationTicket? {
        if (running || activeConfigMutation != null) return null
        return ConfigMutationTicket(++generation).also { activeConfigMutation = it }
    }

    /** Release a Configure claim only when [ticket] still owns it. */
    @Synchronized
    fun finishConfigMutation(ticket: ConfigMutationTicket) {
        if (activeConfigMutation == ticket) activeConfigMutation = null
    }

    /**
     * Atomically turn the current Configure owner into a visible destructive-operation owner. There is
     * deliberately no release/reacquire window: a channel transaction can commit its admitted target,
     * promote this ticket under the same monitor, and then consume the exact staged APK while every
     * competing install/restore caller continues to observe the lane as owned.
     */
    fun promoteConfigMutation(
        ticket: ConfigMutationTicket,
        component: String,
        presentation: InstallPresentation? = null,
    ): Ticket? = promoteConfigMutationLocked(ticket, component, presentation)?.also { notifyObserver() }

    @Synchronized
    private fun promoteConfigMutationLocked(
        ticket: ConfigMutationTicket,
        component: String,
        presentation: InstallPresentation?,
    ): Ticket? {
        if (activeConfigMutation != ticket || running || active != null) return null
        val promoted = Ticket(ticket.id)
        activeConfigMutation = null
        active = promoted
        this.component = component
        this.message = "Working…"
        this.presentation = presentation
        this.result = null
        this.running = true
        return promoted
    }

    /** Verify an explicitly threaded operation ticket without exposing the current owner. */
    @Synchronized
    fun owns(ticket: Ticket): Boolean = running && active == ticket

    /** One coherent progress read so native consumers cannot pair one operation's prose with another's metadata. */
    @Synchronized
    fun presentationSnapshot(): PresentationSnapshot =
        PresentationSnapshot(generation, running, component, message, presentation)

    /** Record [result] only if [ticket] still owns the single progress slot. */
    fun finish(
        ticket: Ticket,
        result: String,
        structured: OperationResult? = null,
        presentation: InstallPresentation? = null,
    ) {
        if (finishLocked(ticket, result, structured, presentation)) notifyObserver()
    }

    @Synchronized
    private fun finishLocked(
        ticket: Ticket,
        result: String,
        structured: OperationResult?,
        presentation: InstallPresentation?,
    ): Boolean {
        if (active != ticket) return false
        this.message = result
        this.result = structured
        this.presentation = presentation
        this.running = false
        this.active = null
        return true
    }

    /** Ensure cancellation before a launched body begins cannot strand the process-global slot busy. */
    fun finishOnFailure(ticket: Ticket, job: Job): Job = job.also {
        it.invokeOnCompletion { cause ->
            if (cause != null) finish(
                ticket,
                "cancelled",
                presentation = InstallPresentation("operation-cancelled"),
            )
        }
    }

    @Synchronized
    fun json(): String = buildString {
        append("{\"running\":").append(running)
        append(",\"component\":").append(esc(component))
        append(",\"message\":").append(esc(message))
        presentation?.let { append(",\"presentation\":").append(it.json()) }
        result?.let { append(",\"result\":").append(resultJson(it)) }
        append('}')
    }

    private fun resultJson(result: OperationResult): String = buildString {
        append("{\"status\":").append(esc(result.status.wireValue))
        result.config?.let { append(",\"config\":").append(componentJson(it)) }
        result.profiles?.let { append(",\"profiles\":").append(componentJson(it)) }
        result.companion?.let { append(",\"companion\":").append(componentJson(it)) }
        result.rollback?.let { append(",\"rollback\":").append(componentJson(it)) }
        result.wakeWords?.let { append(",\"wake_words\":").append(componentJson(it)) }
        append('}')
    }

    private fun componentJson(result: ComponentResult): String = buildString {
        append("{\"status\":").append(esc(result.status.wireValue))
        result.items?.let { append(",\"items\":").append(it.coerceAtLeast(0)) }
        if (result.detail.isNotBlank()) append(",\"detail\":").append(esc(result.detail.take(MAX_DETAIL_CHARS)))
        result.presentation?.let { append(",\"presentation\":").append(it.json()) }
        append('}')
    }

    /** Minimal JSON string escaper — installer results can contain quotes/newlines/backslashes. */
    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            '\n' -> b.append("\\n")
            '\r' -> b.append("\\r")
            '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u%04x".format(c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }

    private const val MAX_DETAIL_CHARS = 256
}
