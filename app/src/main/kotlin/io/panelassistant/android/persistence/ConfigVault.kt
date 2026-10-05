package io.panelassistant.android.persistence

import java.io.File
import java.security.MessageDigest

/**
 * A small, unconditional copy of the only data whose loss is a crisis.
 *
 * Configuration is roughly 0.2% of the database and effectively all of its value: losing a hundred
 * thousand rows of statistics is an annoyance, losing a hundred rows of configuration stops the panel
 * showing a dashboard and a non-technical owner cannot recover it. The existing whole-database
 * pre-migration snapshot protects it only incidentally, and **skips entirely** when the database is too
 * large or free space is short — that is, exactly under the storage pressure that makes loss likely.
 * It also cannot cover imported device profiles, which live outside the database.
 *
 * This vault inverts that: because the payload is tiny it carries no size ceiling and no free-space
 * gate, and it keeps far more generations than a whole-database copy could afford. It is deliberately a
 * flat text dump of the `app_state*` rows rather than a database copy, so it stays readable by any
 * future build and survives corruption of the database file itself.
 *
 * Pure and filesystem-only: rows are supplied by the caller so this is testable without Android.
 */
object ConfigVault {
    const val VAULT_DIRECTORY = "config-vault"
    const val DEFAULT_GENERATIONS = 50

    private const val FORMAT = "hapaneld-config-vault/1"
    private const val HASH_PREFIX = "sha256:"

    /** One `app_state` row. [valueText] is null for a stored null. */
    data class StateRow(
        val namespace: String,
        val key: String,
        val type: String,
        val valueText: String?,
        val updatedAt: Long,
    )

    data class Export(val rows: List<StateRow>, val profiles: Map<String, String>)

    /**
     * Writes a new generation. Returns the file, or null when there is genuinely nothing to protect —
     * never because space was tight. An empty row set is refused so a database that failed to open
     * cannot quietly overwrite good generations with an empty one.
     */
    fun write(vaultDir: File, export: Export, atMillis: Long, keep: Int = DEFAULT_GENERATIONS): File? {
        if (export.rows.isEmpty()) return null
        return runCatching {
            vaultDir.mkdirs()
            val body = encode(export)
            val file = File(vaultDir, "config-$atMillis.vault")
            val temporary = File(vaultDir, file.name + ".partial")
            // Write then rename: a torn write must never be visible as a candidate for restore.
            temporary.writeText(body)
            temporary.renameTo(file)
            prune(vaultDir, keep)
            file.takeIf { it.isFile }
        }.getOrNull()
    }

    /** Generations newest first. Unreadable or corrupt files are skipped, never returned. */
    fun generations(vaultDir: File): List<File> =
        (vaultDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("config-") && it.name.endsWith(".vault") }
            .sortedByDescending { stamp(it) }

    /** The newest generation that verifies, or null when none does. */
    fun newestValid(vaultDir: File): Export? =
        generations(vaultDir).asSequence().mapNotNull { decode(it.readTextOrNull()) }.firstOrNull()

    /**
     * Parses and verifies a generation. Returns null for anything unrecognised, truncated or whose
     * digest does not match: a corrupt export must be discarded rather than restored over live config.
     */
    fun decode(body: String?): Export? {
        if (body == null) return null
        val lines = body.lineSequence().toList()
        if (lines.firstOrNull() != FORMAT) return null
        val digestLine = lines.getOrNull(1)?.takeIf { it.startsWith(HASH_PREFIX) } ?: return null
        val payload = lines.drop(2)
        if (digest(payload.joinToString("\n")) != digestLine.removePrefix(HASH_PREFIX)) return null

        val rows = mutableListOf<StateRow>()
        val profiles = mutableMapOf<String, String>()
        for (line in payload) {
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "S" -> {
                    if (parts.size != 6) return null
                    rows += StateRow(
                        namespace = unescape(parts[1]),
                        key = unescape(parts[2]),
                        type = unescape(parts[3]),
                        valueText = parts[4].takeIf { it != "\u0000" }?.let(::unescape),
                        updatedAt = parts[5].toLongOrNull() ?: return null,
                    )
                }
                "P" -> {
                    if (parts.size != 3) return null
                    profiles[unescape(parts[1])] = unescape(parts[2])
                }
                "" -> Unit
                else -> return null
            }
        }
        return Export(rows, profiles).takeIf { it.rows.isNotEmpty() }
    }

    /**
     * The counterpart to [decode]. Public because the portable backup archive carries the same payload:
     * one codec means a vault generation and a backup entry stay readable by the same parser.
     */
    fun encode(export: Export): String {
        val payload = buildList {
            export.rows.sortedWith(compareBy({ it.namespace }, { it.key })).forEach { row ->
                add(
                    listOf(
                        "S", escape(row.namespace), escape(row.key), escape(row.type),
                        row.valueText?.let(::escape) ?: "\u0000", row.updatedAt.toString(),
                    ).joinToString("\t"),
                )
            }
            export.profiles.toSortedMap().forEach { (name, content) ->
                add(listOf("P", escape(name), escape(content)).joinToString("\t"))
            }
        }.joinToString("\n")
        return "$FORMAT\n$HASH_PREFIX${digest(payload)}\n$payload"
    }

    private fun prune(vaultDir: File, keep: Int) {
        generations(vaultDir).drop(keep.coerceAtLeast(1)).forEach { runCatching { it.delete() } }
        // Abandoned partial writes are never restore candidates; do not let them accumulate.
        (vaultDir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".partial") }
            .forEach { runCatching { it.delete() } }
    }

    private fun stamp(file: File): Long =
        file.name.removePrefix("config-").removeSuffix(".vault").toLongOrNull() ?: 0L

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    private fun digest(payload: String): String =
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // Tab and newline are the record separators, so they must not survive inside a field.
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.length - 1) {
                out.append(character); index++; continue
            }
            when (val next = value[index + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(next)
            }
            index += 2
        }
        return out.toString()
    }
}
