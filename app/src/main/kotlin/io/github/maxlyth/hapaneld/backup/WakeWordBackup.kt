package io.github.maxlyth.hapaneld.backup

import io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * The backup section that carries imported wake words. The settings only name the selected ids, so
 * without the models themselves a restored panel would select a custom word it can never hear.
 *
 * Every imported wake word goes into one archive entry, `{"wake_words":[{"id","manifest","model"}]}` with
 * both files base64-encoded. The section is written only when there is at least one import, so an archive
 * from a panel without any stays restorable by builds that predate it.
 */
object WakeWordBackup {
    const val ENTRY = "voice/wake-words.json"

    /**
     * The encoded entry's bound. Real microWakeWord models are around 60 KiB, so this holds the 32 a panel
     * may import many times over; the theoretical worst case (32 models at the 2 MiB import limit, about
     * 86 MiB encoded) cannot fit a 64 MiB backup at all, so a backup beyond this bound is refused whole
     * rather than written without some of them.
     */
    const val MAX_ENTRY_BYTES = 12L * 1024L * 1024L

    /** The entry's text, or null when there is nothing imported and the section is left out. */
    fun encode(words: List<WakeWordCatalog.ImportedFiles>): String? {
        if (words.isEmpty()) return null
        val encoder = Base64.getEncoder()
        val array = JSONArray()
        for (word in words) {
            array.put(
                JSONObject()
                    .put("id", word.id)
                    .put("manifest", encoder.encodeToString(word.manifest))
                    .put("model", encoder.encodeToString(word.model)),
            )
        }
        return JSONObject().put("wake_words", array).toString()
    }

    /** The manifest's `wake_words` object for an entry of [size] bytes holding [count] wake words. */
    fun manifestFragment(size: Long, count: Int): String =
        JSONObject().put("entry", ENTRY).put("size", size).put("count", count).toString()

    /** The archive entry a manifest's `wake_words` section declares; throws when the section is malformed. */
    fun declaredEntry(section: JSONObject): String {
        require(section.opt("entry") == ENTRY) { "unexpected wake word entry" }
        entrySize(section)
        return ENTRY
    }

    private fun entrySize(section: JSONObject): Long {
        val raw = section.opt("size") as? Number ?: throw IllegalArgumentException("missing wake word size")
        val size = raw.toLong()
        require(raw.toDouble() == size.toDouble() && size in 1..MAX_ENTRY_BYTES) { "invalid wake word size" }
        return size
    }

    /**
     * Extract and decode the section from [archive], which must hold exactly [allowedEntries]. Throws
     * when the entry is missing, the wrong size, or not a wake word list; a model the engine would refuse
     * is still returned here, so the restore can name it rather than lose it.
     */
    fun read(archive: File, section: JSONObject, allowedEntries: Set<String>, stagingDir: File): List<WakeWordCatalog.ImportedFiles> {
        val size = entrySize(section)
        require(declaredEntry(section) in allowedEntries)
        val target = File.createTempFile("wake-word-restore-", ".payload", stagingDir)
        try {
            require(
                PanelBackup.extractArchive(
                    archive,
                    listOf(PanelBackup.ArchiveTarget(ENTRY, target, MAX_ENTRY_BYTES)),
                    allowedEntries,
                ),
            ) { "wake word entry could not be extracted" }
            require(target.length() == size) { "wake word entry size mismatch" }
            return decode(target.readText(Charsets.UTF_8))
        } finally {
            target.delete()
        }
    }

    internal fun decode(text: String): List<WakeWordCatalog.ImportedFiles> {
        val array = JSONObject(text).optJSONArray("wake_words") ?: throw IllegalArgumentException("missing wake_words")
        require(array.length() in 1..WakeWordCatalog.MAX_WAKE_WORDS) { "invalid wake word count" }
        val decoder = Base64.getDecoder()
        val words = (0 until array.length()).map { index ->
            val word = array.optJSONObject(index) ?: throw IllegalArgumentException("invalid wake word")
            val id = word.opt("id") as? String ?: throw IllegalArgumentException("missing wake word id")
            require(WakeWordCatalog.idFor(id) == id) { "invalid wake word id" }
            WakeWordCatalog.ImportedFiles(
                id,
                decoder.decode(word.opt("manifest") as? String ?: throw IllegalArgumentException("missing manifest")),
                decoder.decode(word.opt("model") as? String ?: throw IllegalArgumentException("missing model")),
            )
        }
        require(words.map { it.id }.toSet().size == words.size) { "duplicate wake word id" }
        return words
    }

    /** What a restore did with the wake words it carried. */
    data class Outcome(val restored: List<String>, val refused: List<Pair<String, String>>) {
        /** One short line naming each refused id and why, or empty when every one was restored. */
        fun warning(): String = refused.joinToString("; ") { (id, reason) -> "$id: $reason" }
    }

    /**
     * Put every carried wake word back through [WakeWordCatalog.import], the path a user's import takes,
     * native engine check included. One that is refused leaves the rest, and the panel's other imports,
     * as they were, and is reported rather than dropped.
     */
    fun restore(catalog: WakeWordCatalog, words: List<WakeWordCatalog.ImportedFiles>): Outcome {
        val restored = ArrayList<String>(words.size)
        val refused = ArrayList<Pair<String, String>>()
        for (word in words) {
            when (val result = catalog.import(word.id, word.manifest, word.model)) {
                is WakeWordCatalog.ImportResult.Imported -> restored += result.config.id
                is WakeWordCatalog.ImportResult.Refused -> refused += word.id to result.reason
            }
        }
        return Outcome(restored, refused)
    }
}
