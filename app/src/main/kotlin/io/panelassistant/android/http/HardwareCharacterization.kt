package io.panelassistant.android.http

import java.io.File

/** Small, bounded, public-safe hardware summaries for diagnostics from panels the maintainer cannot access. */
internal object HardwareCharacterization {
    private const val MAX_ENTRIES = 24
    private const val MAX_TEXT = 80

    fun inputDevices(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(Regex("\\n\\s*\\n"))
            .mapNotNull { block ->
                val name = Regex("(?m)^N: Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val handlers = Regex("(?m)^H: Handlers=(.*)$").find(block)?.groupValues?.get(1).orEmpty()
                clean(if (handlers.isBlank()) name else "$name ($handlers)")
            }
            .distinct()
            .take(MAX_ENTRIES)
    }

    fun namedDevices(parent: File, valueFile: String): List<String> =
        runCatching {
            parent.listFiles().orEmpty()
                .sortedBy { it.name }
                .mapNotNull { node ->
                    val value = runCatching { File(node, valueFile).readText() }.getOrNull()?.let(::clean)
                    value?.takeIf { it.isNotBlank() }?.let { clean("${node.name}:$it") }
                }
                .distinct()
                .take(MAX_ENTRIES)
        }.getOrDefault(emptyList())

    fun relayClasses(paths: List<File>): List<String> = paths.flatMap { parent ->
        runCatching { parent.listFiles().orEmpty().map { clean("${parent.name}/${it.name}") } }
            .getOrDefault(emptyList())
    }.distinct().sorted().take(MAX_ENTRIES)

    private fun clean(value: String): String = value
        .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
        .trim()
        .take(MAX_TEXT)
}
