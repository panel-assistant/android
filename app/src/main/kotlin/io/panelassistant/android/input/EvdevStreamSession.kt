package io.panelassistant.android.input

import io.panelassistant.android.device.EvdevButton
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter

/** The deterministic app side of the helper's WATCH/SUBSCRIBE line protocol. */
internal object EvdevStreamSession {
    /** Suffix for the release (up edge) event type of a button that opts in with `release=true`. */
    const val RELEASED_SUFFIX = "_RELEASED"

    enum class Mode { RECONFIGURABLE, VERIFIED, LEGACY }

    private data class ParsedEvent(val valid: Boolean, val eventType: String?)

    /**
     * Establish every requested watch, require its exact acknowledgement, then consume events until EOF.
     * The helper registers a subscriber before writing its acknowledgement, so a real event can race ahead
     * of that `OK`; those early events are delivered rather than mistaken for a failed handshake.
     */
    fun run(
        buttons: List<EvdevButton>,
        input: InputStream,
        output: OutputStream,
        onSubscribed: (Mode) -> Unit = {},
        emit: (String) -> Unit,
    ) {
        require(buttons.isNotEmpty()) { "an evdev session needs at least one button" }
        require(buttons.distinctBy { it.sw to it.code }.size == buttons.size) {
            "evdev button mappings must have unique type/code pairs"
        }
        buttons.forEach { button ->
            require(button.node.matches(Regex("^/dev/input/event\\d+$"))) { "invalid evdev node: ${button.node}" }
            require(button.code in 1..0xffff) { "invalid evdev code: ${button.code}" }
            require(button.eventType.matches(Regex("^KEYCODE_[A-Z0-9_]+$"))) { "invalid evdev event type: ${button.eventType}" }
        }

        val reader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
        val writer = OutputStreamWriter(output, Charsets.US_ASCII)
        writer.write("INPUTV3\n")
        writer.flush()
        val mode = when (val reply = reader.readLine()) {
            "OK" -> Mode.RECONFIGURABLE
            "ERR" -> {
                writer.write("INPUTV2\n")
                writer.flush()
                when (val legacyReply = reader.readLine()) {
                    "OK" -> Mode.VERIFIED
                    "ERR" -> Mode.LEGACY
                    null -> throw IOException("helper closed before INPUTV2 acknowledgement")
                    else -> throw IOException("unexpected INPUTV2 acknowledgement: $legacyReply")
                }
            }
            null -> throw IOException("helper closed before INPUTV3 acknowledgement")
            else -> throw IOException("unexpected INPUTV3 acknowledgement: $reply")
        }
        if (mode == Mode.RECONFIGURABLE) {
            writer.write("WATCHRESET\n")
            writer.flush()
            requireAck(reader, "WATCHRESET")
        }
        buttons.forEach { button ->
            writer.write("WATCH ${button.node} ${if (button.grab) 1 else 0}\n")
            writer.flush()
            requireAck(reader, "WATCH ${button.node}")
        }

        writer.write("SUBSCRIBE\n")
        writer.flush()
        var line = reader.readLine() ?: throw IOException("helper closed before SUBSCRIBE acknowledgement")
        while (line != "OK") {
            val parsed = parseEvent(line, buttons)
            if (!parsed.valid) throw IOException("helper rejected SUBSCRIBE: $line")
            parsed.eventType?.let(emit)
            line = reader.readLine() ?: throw IOException("helper closed before SUBSCRIBE acknowledgement")
        }
        onSubscribed(mode)

        while (true) {
            val eventLine = reader.readLine() ?: return
            parseEvent(eventLine, buttons).eventType?.let(emit)
        }
    }

    private fun requireAck(reader: BufferedReader, operation: String) {
        val reply = reader.readLine() ?: throw IOException("helper closed before $operation acknowledgement")
        if (reply != "OK") throw IOException("helper rejected $operation: $reply")
    }

    private fun parseEvent(line: String, buttons: List<EvdevButton>): ParsedEvent {
        val fields = line.split(' ')
        if (fields.size != 3) return ParsedEvent(false, null)
        val sw = when (fields[0]) {
            "KEY" -> false
            "SW" -> true
            else -> return ParsedEvent(false, null)
        }
        val code = fields[1].toIntOrNull() ?: return ParsedEvent(false, null)
        val value = fields[2].toIntOrNull() ?: return ParsedEvent(false, null)
        val button = buttons.firstOrNull { it.sw == sw && it.code == code } ?: return ParsedEvent(true, null)
        // Press (value 1) fires the base event type, unchanged. The up edge (value 0) is reported only
        // when the button opts in with release=true, as a distinct <base>_RELEASED type; otherwise the
        // existing behaviour stands (keys are press-only; a switch still reports its state change as the
        // base type on both edges). value 2 is kernel autorepeat and never a physical edge.
        val emitted = when (value) {
            1 -> button.eventType
            0 -> if (button.release) button.eventType + RELEASED_SUFFIX else button.eventType.takeIf { button.sw }
            else -> null
        }
        return ParsedEvent(true, emitted)
    }
}
