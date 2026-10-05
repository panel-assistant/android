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
        val button = buttons.firstOrNull { it.sw == sw && it.code == code }
        val shouldEmit = if (sw) value == 0 || value == 1 else value == 1
        return ParsedEvent(true, button?.eventType?.takeIf { shouldEmit })
    }
}
