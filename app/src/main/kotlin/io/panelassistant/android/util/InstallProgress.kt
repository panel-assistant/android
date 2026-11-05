package io.panelassistant.android.util

import kotlinx.coroutines.Job

/**
 * Process-wide ownership and progress for destructive control-plane operations. HTTP, Home Assistant, and scheduled
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
        /** Admitted durable state and owner choices, with withheld/failed values accounted separately. */
        val state: ComponentResult? = null,
        /** The operation never finished: the app process ended part way, and a later process reports it. */
        val interrupted: Boolean = false,
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
    private var onSuperseded: (() -> Unit)? = null

    /**
     * Told after every claim or release of the visible operation lane, outside this object's monitor.
     * The service republishes the Home Assistant update entities' progress from it; this object stays transport-free.
     */
    @Volatile var observer: (() -> Unit)? = null

    private fun notifyObserver() {
        observer?.let { runCatching { it() } }
    }

    /** Claim the operation lane for [component]. Returns null if another owner is already in flight. */
    fun start(component: String, presentation: InstallPresentation? = null): Ticket? {
        var superseded: (() -> Unit)? = null
        val ticket = startLocked(component, presentation) { superseded = it } ?: return null
        superseded?.let { runCatching { it() } }
        notifyObserver()
        return ticket
    }

    @Synchronized
    private fun startLocked(
        component: String,
        presentation: InstallPresentation?,
        supersede: (() -> Unit) -> Unit,
    ): Ticket? {
        if (running || activeConfigMutation != null) return null
        onSuperseded?.let(supersede)
        onSuperseded = null
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

    /** Verify an explicitly threaded operation ticket without exposing the current owner. */
    @Synchronized
    fun owns(ticket: Ticket): Boolean = running && active == ticket

    /** One coherent progress read so native consumers cannot pair one operation's prose with another's metadata. */
    @Synchronized
    fun presentationSnapshot(): PresentationSnapshot =
        PresentationSnapshot(generation, running, component, message, presentation)

    /**
     * Replace the running operation's prose and presentation, for a stage the caller reaches inside it
     * (Android's install confirmation waiting for a tap). The slot is single-owner, so the running
     * operation is the caller's; nothing changes when no operation runs.
     */
    fun presentRunning(message: String, presentation: InstallPresentation) {
        if (presentRunningLocked(message, presentation)) notifyObserver()
    }

    @Synchronized
    private fun presentRunningLocked(message: String, presentation: InstallPresentation): Boolean {
        if (!running) return false
        this.message = message
        this.presentation = presentation
        return true
    }

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

    /**
     * Report a finished operation this process never ran: one an earlier process left unfinished when it
     * ended. Nothing changes while an operation runs. [onSuperseded] runs once, when the next operation
     * claims the lane, so the durable record behind this report outlives every restart until then.
     */
    fun reportFinished(
        component: String,
        result: String,
        structured: OperationResult,
        presentation: InstallPresentation,
        onSuperseded: () -> Unit,
    ): Boolean = reportFinishedLocked(component, result, structured, presentation, onSuperseded)
        .also { if (it) notifyObserver() }

    @Synchronized
    private fun reportFinishedLocked(
        component: String,
        result: String,
        structured: OperationResult,
        presentation: InstallPresentation,
        onSuperseded: () -> Unit,
    ): Boolean {
        if (running || activeConfigMutation != null) return false
        this.component = component
        this.message = result
        this.result = structured
        this.presentation = presentation
        this.onSuperseded = onSuperseded
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
        append(",\"component\":").append(Json.str(component))
        append(",\"message\":").append(Json.str(message))
        presentation?.let { append(",\"presentation\":").append(it.json()) }
        result?.let { append(",\"result\":").append(resultJson(it)) }
        append('}')
    }

    private fun resultJson(result: OperationResult): String = buildString {
        append("{\"status\":").append(Json.str(result.status.wireValue))
        result.config?.let { append(",\"config\":").append(componentJson(it)) }
        result.profiles?.let { append(",\"profiles\":").append(componentJson(it)) }
        result.companion?.let { append(",\"companion\":").append(componentJson(it)) }
        result.rollback?.let { append(",\"rollback\":").append(componentJson(it)) }
        result.wakeWords?.let { append(",\"wake_words\":").append(componentJson(it)) }
        result.state?.let { append(",\"state\":").append(componentJson(it)) }
        if (result.interrupted) append(",\"interrupted\":true")
        append('}')
    }

    private fun componentJson(result: ComponentResult): String = buildString {
        append("{\"status\":").append(Json.str(result.status.wireValue))
        result.items?.let { append(",\"items\":").append(it.coerceAtLeast(0)) }
        if (result.detail.isNotBlank()) append(",\"detail\":").append(Json.str(result.detail.take(MAX_DETAIL_CHARS)))
        result.presentation?.let { append(",\"presentation\":").append(it.json()) }
        append('}')
    }


    private const val MAX_DETAIL_CHARS = 256
}
