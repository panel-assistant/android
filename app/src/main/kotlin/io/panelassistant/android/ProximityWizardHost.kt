package io.panelassistant.android

import org.json.JSONObject

internal fun proximityWizardMayRebind(stage: String): Boolean =
    stage in setOf("saved", "cancelled", "timed_out", "failed", "unavailable")

internal fun proximityWizardMustCancelOnStop(stage: String, changingConfigurations: Boolean): Boolean =
    !changingConfigurations && !proximityWizardMayRebind(stage)

/** Process-local bridge: only the service owns calibration; Activities only present a live session. */
object ProximityWizardHost {
    private data class Binding(
        val owner: Any,
        val status: () -> String,
        val action: (String) -> Boolean,
        val narrate: (String, String, String) -> Boolean,
        val stopNarration: () -> Unit,
    )
    private var binding: Binding? = null

    @Synchronized
    fun attach(
        owner: Any,
        status: () -> String,
        action: (String) -> Boolean,
        narrate: (String, String, String) -> Boolean = { _, _, _ -> false },
        stopNarration: () -> Unit = {},
    ) {
        binding = Binding(owner, status, action, narrate, stopNarration)
    }

    @Synchronized
    fun detach(owner: Any) {
        if (binding?.owner === owner) binding = null
    }

    @Synchronized
    fun status(): String? = binding?.let { runCatching(it.status).getOrNull() }

    /** Never let a restored or dismissed Activity operate on a later session. */
    @Synchronized
    fun action(sessionId: String, action: String): Boolean {
        if (sessionId.isBlank() || action !in ACTIONS) return false
        val current = binding ?: return false
        return runCatching {
            if (JSONObject(current.status()).optString("sessionId") != sessionId) false
            else current.action(action)
        }.getOrDefault(false)
    }

    @Synchronized
    fun narrate(sessionId: String, prompt: String, text: String, localeTag: String): Boolean {
        if (sessionId.isBlank() || prompt.isBlank() || text.isBlank() || localeTag.isBlank()) return false
        val current = binding ?: return false
        return runCatching {
            if (JSONObject(current.status()).optString("sessionId") != sessionId) false
            else current.narrate(prompt, text, localeTag)
        }.getOrDefault(false)
    }

    @Synchronized
    fun stopNarration(sessionId: String) {
        if (sessionId.isBlank()) return
        val current = binding ?: return
        runCatching {
            if (JSONObject(current.status()).optString("sessionId") == sessionId) current.stopNarration()
        }
    }

    private val ACTIONS = setOf("visible", "begin", "retry", "save", "cancel")
}
