package io.panelassistant.android.panelassistant

import org.json.JSONObject

/** Administrator-only operations. This door is never exposed to the browser/UI dispatcher. */
internal interface PanelAssistantLocalAdb {
    /** Whether the hello offers local ADB: only when this panel could reach its root endpoint. */
    fun offered(): Boolean = true
    fun session(token: String, granted: Boolean)
    suspend fun request(op: String, args: JSONObject, admit: () -> Boolean, onPending: () -> Unit = {}): PanelAssistantManageReply
}
