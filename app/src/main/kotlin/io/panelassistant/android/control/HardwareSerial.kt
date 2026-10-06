package io.panelassistant.android.control

import io.panelassistant.android.util.PanelAssistantDevice

/**
 * The panel's hardware serial, `ro.serialno`. Android's policy forbids apps that property, so only `su`
 * reads it; elsewhere it is unknown and the caller falls back to the Android id. The caller passes
 * whether `su` itself is ready: the root helper is a different route, and asking [Su] on a panel that
 * has only the helper would fail on every poll.
 *
 * It cannot change while the app runs, so one answer from root is kept, an empty one included, and the
 * status poll never starts a root shell again. A root shell that failed is tried on a later read.
 */
internal class HardwareSerial(private val runRoot: (String) -> String?) {
    @Volatile private var answered = false
    @Volatile private var serial: String? = null

    fun read(rootReady: Boolean): String? {
        if (answered) return serial
        if (!rootReady) return null
        val output = runRoot(COMMAND) ?: return null
        serial = PanelAssistantDevice.field(output)
        answered = true
        return serial
    }

    companion object {
        /** Fixed: nothing from outside ever enters this root command. */
        const val COMMAND = "/system/bin/getprop ro.serialno"
        private const val MAX_OUTPUT_BYTES = 256L
        private const val TIMEOUT_MS = 2_000L

        val shared = HardwareSerial { Su.runOutputIsolatedBounded(it, MAX_OUTPUT_BYTES, TIMEOUT_MS) }
    }
}
