package io.panelassistant.android.panelassistant

import org.json.JSONObject

/** Administrator-only operations. This door is never exposed to the browser/UI dispatcher. */
internal interface PanelAssistantLocalAdb {
    fun session(token: String, granted: Boolean)
    suspend fun request(op: String, args: JSONObject, admit: () -> Boolean, onPending: () -> Unit = {}): PanelAssistantManageReply
}
