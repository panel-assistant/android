package io.panelassistant.android.shizuku

/**
 * Pure policy for the Shizuku shell-UID room-climate read. Device paths are derived only from exact
 * kernel input names, constrained to event nodes, and paired by a compiled layout. No caller supplies a
 * path, device name, axis, or command argument.
 */
internal object RoomClimateShellReader {
    private data class Layout(
        val temperatureName: String,
        val temperatureAxis: String,
        val humidityName: String,
        val humidityAxis: String,
    )

    private val layouts = listOf(
        Layout("temperature", "ABS_THROTTLE", "humidity", "ABS_THROTTLE"),
        Layout("sun-ths", "ABS_THROTTLE", "sun-hum", "001d"),
    )

    fun read(inventory: String, getEventProperties: (String) -> String?): String? {
        val candidates = mutableListOf<Triple<Layout, String, String>>()
        for (layout in layouts) {
            val temperature = eventNode(inventory, layout.temperatureName)
            val humidity = eventNode(inventory, layout.humidityName)
            if (!temperature.observed && !humidity.observed) continue
            val temperatureNode = temperature.node ?: return null
            val humidityNode = humidity.node ?: return null
            candidates += Triple(layout, temperatureNode, humidityNode)
        }
        val (layout, temperatureNode, humidityNode) = candidates.singleOrNull() ?: return null
        val temperature = getEventProperties(temperatureNode)
            ?.let { axisValue(it, layout.temperatureAxis) } ?: return null
        val humidity = getEventProperties(humidityNode)
            ?.let { axisValue(it, layout.humidityAxis) } ?: return null
        return "T=$temperature H=$humidity"
    }

    private data class EventNode(val observed: Boolean, val node: String?)

    private fun eventNode(inventory: String, exactName: String): EventNode {
        val blocks = inventory
            .split(Regex("\\r?\\n\\s*\\r?\\n"))
            .filter { block ->
                block.lineSequence().any { it.trim() == "N: Name=\"$exactName\"" }
            }
        val nodes = blocks
            .flatMap { block ->
                block.lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith("H: Handlers=") }
                    .flatMap { line -> line.removePrefix("H: Handlers=").trim().split(Regex("\\s+")).asSequence() }
                    .filter { it.matches(EVENT_NAME) }
                    .map { "/dev/input/$it" }
                    .toList()
            }
        return EventNode(
            observed = blocks.isNotEmpty(),
            node = nodes.singleOrNull().takeIf { blocks.size == 1 },
        )
    }

    private fun axisValue(properties: String, exactAxis: String): Long? {
        val match = Regex(
            "^\\s*ABS\\s+\\([0-9a-f]{4}\\):\\s*${Regex.escape(exactAxis)}\\s*:\\s*value\\s+(-?\\d+),",
            setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
        ).findAll(properties).toList().singleOrNull() ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private val EVENT_NAME = Regex("event(?:0|[1-9][0-9]{0,2})")
}
