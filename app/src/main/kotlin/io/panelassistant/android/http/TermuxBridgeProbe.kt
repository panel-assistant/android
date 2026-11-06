package io.panelassistant.android.http

import java.util.Locale

/**
 * Read-only process classification. A runit/pm2 supervisor alone does not mean the bridge is running.
 *
 * This runs on every :8888 dashboard render (behind a short cache), so it is a repeated read and never
 * uses su: the helper's `PERFDUMP BRIDGE <uid>` classifies the Termux-owned processes (owner, argv and
 * cwd) inside the helper. Without a helper, or with one too old to answer the marker, the state is unknown.
 */
internal object TermuxBridgeProbe {
    enum class State { NOT_APPLICABLE, RUNNING, ABSENT, UNKNOWN }

    fun diagnosticLine(state: State): String? =
        if (state == State.NOT_APPLICABLE) null
        else "[panel-zigbee-bridge] state=${state.name.lowercase(Locale.ROOT)}"

    /** The production route collector: no Termux is not applicable; otherwise ask a ready helper. */
    fun collect(
        termuxUid: Result<Int?>,
        helperReady: () -> Boolean,
        helperRun: (String) -> String?,
    ): State {
        if (termuxUid.isFailure) return State.UNKNOWN
        val uid = termuxUid.getOrNull() ?: return State.NOT_APPLICABLE
        return observe(if (helperReady()) helperRun("PERFDUMP BRIDGE $uid") else null)
    }

    /** Classify an installed Termux from the helper's dump; no dump, or an old helper's, is unknown. */
    fun observe(helperDump: String?): State {
        if (helperDump == null || !helperDump.contains("@PROC\n") || !helperDump.contains("@END")) return State.UNKNOWN
        // Old helpers do not know the UID/cwd-aware marker and cannot prove a negative.
        return when (helperDump.lineSequence().firstOrNull { it.startsWith("@BRIDGE ") }
            ?.removePrefix("@BRIDGE ")?.trim()) {
            "1" -> State.RUNNING
            "0" -> State.ABSENT
            else -> State.UNKNOWN
        }
    }
}
