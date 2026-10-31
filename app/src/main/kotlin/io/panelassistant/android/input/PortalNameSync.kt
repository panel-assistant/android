package io.panelassistant.android.input

/** Bounded driver for the ordinary, authenticated Portal name editor. All calls run on main. */
internal class PortalNameSync(
    private val now: () -> Long,
    private val window: () -> Window?,
    private val launch: () -> Boolean,
    private val act: (Window, Action, String) -> Boolean,
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val returnToDashboard: () -> Unit,
    private val finished: (Boolean, String) -> Unit,
) : AutoCloseable {
    data class Node(
        val id: String = "", val kind: String = "", val text: String? = null,
        val enabled: Boolean = true, val visible: Boolean = true, val clickable: Boolean = false,
        val children: List<Node> = emptyList(),
    ) {
        fun all(id: String): List<Node> = (if (this.id == id) listOf(this) else emptyList()) + children.flatMap { it.all(id) }
        fun usable(kind: String, click: Boolean = false) = visible && enabled && this.kind == kind && (!click || clickable)
    }
    data class Window(val id: Int, val packageName: String?, val dashboard: Boolean, val root: Node) {
        // Anchor on the editor's actionable controls only. Meta's containers (qa_device_setup_name,
        // names_niu_list, the dialog root) are plain layouts that a default accessibility service is
        // never served; their important children arrive lifted to the window root.
        fun editor(): Node? = root.takeIf {
            it.all(ID + "niu_list").size == 1 && it.all(ID + "next").size == 1 && it.all(ID + "niu_dialog_input").isEmpty()
        }
        fun row(): Node? {
            val list = editor()?.all(ID + "niu_list")?.singleOrNull()?.takeIf {
                it.usable("androidx.recyclerview.widget.RecyclerView")
            } ?: return null
            // The first direct adapter row is the custom name; its label changes with the name.
            return list.children.firstOrNull()?.takeIf {
                it.usable("android.widget.Button", true) && it.children.count { title ->
                    title.id == ID + "title" && title.usable("android.widget.TextView") && title.text != null
                } == 1
            }
        }
        fun name(): String? = row()?.children?.singleOrNull { it.id == ID + "title" }?.text
        fun dialog(): Node? = root.takeIf {
            it.all(ID + "niu_dialog_input").size == 1 && it.all(ID + "niu_dialog_button_positive").size == 1
        }
        fun target(action: Action): Node? = when (action) {
            Action.CUSTOM -> row()
            Action.TEXT -> dialog()?.all(ID + "niu_dialog_input")?.singleOrNull()?.takeIf { it.usable("android.widget.EditText") }
            Action.SAVE -> dialog()?.all(ID + "niu_dialog_button_positive")?.singleOrNull()?.takeIf { it.usable("android.widget.Button", true) }
            Action.DONE -> editor()?.all(ID + "next")?.singleOrNull()?.takeIf { it.usable("android.widget.Button", true) }
            Action.BACK -> editor()?.takeIf { row() != null }
        }
    }
    enum class Action { CUSTOM, TEXT, SAVE, DONE, BACK }
    private enum class Phase { OPEN, DIALOG, TEXT, LIST, CLOSED, REOPEN }
    private var phase = Phase.OPEN
    private var desired = ""
    private var started = 0L
    private var dashboardSince: Long? = null
    private var originId: Int? = null
    private var editorId: Int? = null
    private var dialogId: Int? = null
    private var readbacks = 0
    private var running = false
    private var tickQueued = false
    private var awaitingReplacement = false
    private var draftChanged = false
    private var launched = false
    val active get() = running

    fun request(name: String) {
        if (running) {
            // The latest intent replaces an unsent name. A submitted save must first close normally.
            desired = name
            awaitingReplacement = true
            started = now()
            return
        }
        desired = name
        started = now()
        running = true
        val initial = window()
        if (name.isBlank() || initial?.dashboard != true) {
            finish(false, "Portal rename requires the dashboard in front")
            return
        }
        originId = initial.id
        if (!launch()) { finish(false, "Portal name editor could not be opened"); return }
        launched = true
        schedule()
    }

    fun onEvent() { if (running) schedule(100L) }
    private fun schedule(delay: Long = POLL_MS) {
        if (tickQueued) return
        tickQueued = true
        post(tick, delay)
    }
    private val tick = Runnable {
        tickQueued = false
        if (running) {
            if (now() - started >= TIMEOUT_MS) finish(false, "Portal rename timed out; authentication or vendor save may still be pending")
            else {
                runCatching { advance(window()) }.onFailure { finish(false, "Portal editor could not be read or operated") }
                if (running) schedule()
            }
        }
    }

    private fun advance(current: Window?) {
        val w = current ?: return
        if (w.packageName != SETTINGS && !w.dashboard) {
            // Auth and system dialogs are left untouched. The timer still bounds the operation.
            return
        }
        if (awaitingReplacement && phase != Phase.CLOSED && phase != Phase.REOPEN) {
            if (w.packageName == SETTINGS && w.row() != null && (editorId == null || editorId == w.id)) {
                phase = Phase.OPEN
                awaitingReplacement = false
            } else if (w.packageName == SETTINGS && w.dialog() != null && dialogId == w.id) {
                phase = Phase.DIALOG
                awaitingReplacement = false
            }
        }
        when (phase) {
            Phase.OPEN -> if (w.packageName == SETTINGS && w.row() != null) {
                editorId = w.id
                if (w.name() == desired && !draftChanged) verified(w)
                else if (w.name() == desired) perform(w, Action.DONE, Phase.CLOSED)
                else perform(w, Action.CUSTOM, Phase.DIALOG)
            }
            Phase.DIALOG -> if (w.packageName == SETTINGS && w.dialog() != null) {
                dialogId = w.id
                perform(w, Action.TEXT, Phase.TEXT)
            }
            Phase.TEXT -> if (w.id == dialogId && w.target(Action.TEXT)?.text == desired) {
                perform(w, Action.SAVE, Phase.LIST)
            }
            Phase.LIST -> if (w.id == editorId && w.row() != null && w.name() == desired) {
                perform(w, Action.DONE, Phase.CLOSED)
            }
            Phase.CLOSED -> if (w.dashboard && w.id == originId) {
                val since = dashboardSince ?: now().also { dashboardSince = it }
                if (now() - since >= SAVE_SETTLE_MS) {
                    phase = Phase.REOPEN
                    dashboardSince = null
                    if (!launch()) finish(false, "Portal saved name could not be reopened for verification")
                }
            } else dashboardSince = null
            Phase.REOPEN -> if (w.packageName == SETTINGS && w.row() != null) {
                editorId = w.id
                if (awaitingReplacement) {
                    draftChanged = false
                    awaitingReplacement = false
                    phase = Phase.OPEN
                } else if (w.name() == desired) verified(w)
                else if (++readbacks < MAX_READBACKS) {
                    // A newly opened editor can still show the pre-save cloud value. Reopen later.
                    perform(w, Action.BACK, Phase.CLOSED)
                } else {
                    act(w, Action.BACK, desired)
                    finish(false, "Portal persisted name did not match the requested name")
                }
            }
        }
    }
    private fun perform(w: Window, action: Action, next: Phase) {
        if (w.target(action) == null) return
        phase = next
        if (action == Action.SAVE) draftChanged = true
        if (!act(w, action, desired)) finish(false, "Portal editor refused ${action.name.lowercase()}")
    }
    private fun verified(w: Window) {
        val returned = act(w, Action.BACK, desired)
        finish(true, if (returned) "Portal name verified in native editor" else "Portal name verified; editor return was refused")
    }
    private fun finish(success: Boolean, reason: String) {
        if (!running) return
        running = false
        cancel(tick)
        tickQueued = false
        // A failed rename must not leave Meta's editor (or whatever it led to) over the dashboard.
        if (!success && launched && runCatching { window()?.dashboard }.getOrNull() != true) returnToDashboard()
        launched = false
        finished(success, reason)
    }
    override fun close() { finish(false, "Portal rename cancelled") }

    companion object {
        const val SETTINGS = "com.facebook.alohaapps.settings"
        const val ID = "$SETTINGS:id/"
        const val ACTION = "com.facebook.aloha.system.settings.PORTAL_NAME"
        const val TIMEOUT_MS = 120_000L
        const val SAVE_SETTLE_MS = 3_000L
        const val POLL_MS = 300L
        const val MAX_READBACKS = 8
    }
}
