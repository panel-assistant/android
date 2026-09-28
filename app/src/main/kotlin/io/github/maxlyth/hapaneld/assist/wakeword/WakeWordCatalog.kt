package io.github.maxlyth.hapaneld.assist.wakeword

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every wake word this panel can listen for: the bundled Apache-2.0 microWakeWord models, then the ones
 * a user imported. An import is a microWakeWord manifest (`.json`) and its model (`.tflite`), validated by
 * the native engine before anything can use it; a rejected import leaves the last good model of that id
 * exactly as it was.
 */
class WakeWordCatalog(
    private val bundled: BundledModels,
    /** Where imported models live, one directory per id. */
    private val importDir: File,
    /** Whether the native engine accepts a model; injected so the file handling is testable. */
    private val accepts: (ByteBuffer, MicroWakeWordModelConfig) -> Boolean = { buffer, config ->
        NativeMicroWakeWord.create(buffer, config)?.let { it.close(); true } ?: false
    },
) {
    constructor(context: Context) : this(AssetModels(context), File(context.filesDir, IMPORT_DIR))

    /** The bundled models, read from wherever the APK keeps them. */
    interface BundledModels {
        fun ids(): List<String>

        @Throws(IOException::class)
        fun manifest(id: String): String

        @Throws(IOException::class)
        fun model(file: String): ByteArray
    }

    private class AssetModels(private val context: Context) : BundledModels {
        override fun ids(): List<String> = context.assets.list(MicroWakeWordModelConfig.ASSET_DIR).orEmpty()
            .filter { it.endsWith(JSON) }.map { it.removeSuffix(JSON) }.sorted()

        override fun manifest(id: String): String =
            context.assets.open("${MicroWakeWordModelConfig.ASSET_DIR}/$id$JSON").bufferedReader().use { it.readText() }

        override fun model(file: String): ByteArray =
            context.assets.open("${MicroWakeWordModelConfig.ASSET_DIR}/$file").use { it.readBytes() }
    }

    sealed interface ImportResult {
        data class Imported(val config: MicroWakeWordModelConfig) : ImportResult
        data class Refused(val reason: String) : ImportResult
    }

    /** Whether [id] names a model shipped with the app rather than one a user imported. */
    fun isBundled(id: String): Boolean = id in bundled.ids()

    /** Every usable model's manifest, bundled first; one that no longer parses is left out. */
    @Synchronized
    fun available(): List<MicroWakeWordModelConfig> {
        recover()
        val bundledIds = bundled.ids()
        val found = bundledIds.mapNotNull { id -> runCatching { MicroWakeWordModelConfig.parse(id, bundled.manifest(id)) }.getOrNull() }
        val imported = importDir.listFiles { file -> file.isDirectory && ID.matches(file.name) }.orEmpty()
            .map { it.name }.filter { it !in bundledIds }.sorted()
            .mapNotNull { id -> runCatching { importedConfig(id) }.getOrNull() }
            .filter { unfit(it) == null }
        return (found + imported).take(MAX_WAKE_WORDS)
    }

    /** Load a model by id with the native engine; null when the id is unknown or the engine refuses it. */
    fun load(id: String): LoadedWakeWordModel? {
        if (!ID.matches(id)) return null
        synchronized(this) { recover() }
        val (config, bytes) = try {
            if (id in bundled.ids()) {
                val config = MicroWakeWordModelConfig.parse(id, bundled.manifest(id))
                config to bundled.model(config.modelFile)
            } else {
                val config = importedConfig(id)
                config to File(File(importDir, id), config.modelFile).readBytes()
            }
        } catch (_: Exception) {
            return null
        }
        val scorer = NativeMicroWakeWord.create(direct(bytes), config) ?: return null
        return LoadedWakeWordModel(config, scorer)
    }

    /**
     * Import a user-trained model. [name] names the wake word's id, reduced to lower case letters, digits
     * and underscores. The files are staged beside the catalogue, checked by the engine, then swapped in
     * whole; a failure at any step leaves the previous model of that id untouched.
     */
    @Synchronized
    fun import(name: String, manifest: ByteArray, model: ByteArray): ImportResult {
        val id = idFor(name) ?: return ImportResult.Refused("the wake word needs a name made of letters or digits")
        if (id in bundled.ids()) return ImportResult.Refused("\"$id\" is a bundled wake word")
        if (manifest.size > MAX_MANIFEST_BYTES) return ImportResult.Refused("the .json file is too large")
        if (model.isEmpty() || model.size > MAX_MODEL_BYTES) return ImportResult.Refused("the .tflite file is empty or too large")
        val config = try {
            MicroWakeWordModelConfig.parse(id, manifest.toString(Charsets.UTF_8))
        } catch (invalid: IllegalArgumentException) {
            return ImportResult.Refused(invalid.message ?: "the .json file is not a microWakeWord manifest")
        }
        unfit(config)?.let { return ImportResult.Refused(it) }
        if (config.modelFile == "$id$JSON") return ImportResult.Refused("the model file cannot share the manifest's name")
        if (!File(importDir, id).isDirectory && available().size >= MAX_WAKE_WORDS) {
            return ImportResult.Refused("a panel holds at most $MAX_WAKE_WORDS wake words")
        }
        if (!accepts(direct(model), config)) {
            return ImportResult.Refused("the model was not accepted by the wake-word engine")
        }
        importDir.mkdirs()
        val staging = File(importDir, ".staging-$id")
        staging.deleteRecursively()
        try {
            if (!staging.mkdirs()) throw IOException("cannot stage $id")
            File(staging, "$id$JSON").writeBytes(manifest)
            File(staging, config.modelFile).writeBytes(model)
            val target = File(importDir, id)
            val previous = File(importDir, ".previous-$id")
            previous.deleteRecursively()
            if (target.exists() && !target.renameTo(previous)) throw IOException("cannot set aside $id")
            if (!staging.renameTo(target)) {
                previous.renameTo(target)
                throw IOException("cannot install $id")
            }
            previous.deleteRecursively()
        } catch (failed: IOException) {
            staging.deleteRecursively()
            return ImportResult.Refused(failed.message ?: "the model could not be saved")
        }
        return ImportResult.Imported(config)
    }

    /** One imported wake word's files, as a backup carries them and [import] takes them back. */
    class ImportedFiles(val id: String, val manifest: ByteArray, val model: ByteArray)

    /**
     * The files of every imported wake word [available] lists, for a backup. Throws [IOException] when one
     * cannot be read: a backup that silently left it out could never restore it.
     */
    @Synchronized
    @Throws(IOException::class)
    fun exportImported(): List<ImportedFiles> {
        val bundledIds = bundled.ids()
        return available().filter { it.id !in bundledIds }.map { config ->
            val dir = File(importDir, config.id)
            try {
                ImportedFiles(config.id, File(dir, "${config.id}$JSON").readBytes(), File(dir, config.modelFile).readBytes())
            } catch (unreadable: IOException) {
                throw IOException("imported wake word ${config.id} could not be read", unreadable)
            }
        }
    }

    /**
     * Finish an import the process did not live to complete. A crash between setting the working model
     * aside and moving its replacement in leaves only `.previous-<id>`: that model is put back. A crash
     * after the swap leaves both, and the new one stands. Staging directories are never in use outside
     * [import], which holds this object's lock, so any found here are abandoned.
     */
    private fun recover() {
        val leftovers = importDir.listFiles { file -> file.name.startsWith(".") }.orEmpty()
        for (dir in leftovers) {
            val name = dir.name
            when {
                name.startsWith(".previous-") -> {
                    val id = name.removePrefix(".previous-")
                    val target = File(importDir, id)
                    if (ID.matches(id) && !target.exists()) dir.renameTo(target) else dir.deleteRecursively()
                }
                name.startsWith(".staging-") -> dir.deleteRecursively()
            }
        }
    }

    private fun importedConfig(id: String): MicroWakeWordModelConfig =
        MicroWakeWordModelConfig.parse(id, File(File(importDir, id), "$id$JSON").readText())

    companion object {
        const val IMPORT_DIR = "wakeword"

        /** Home Assistant's own bounds on a satellite's wake words; one outside them refuses them all. */
        const val MAX_WAKE_WORDS = 32
        private const val MAX_PHRASE = 64
        private const val MAX_LANGUAGES = 16
        private const val MAX_LANGUAGE = 16
        private val CONTROL = Regex("[\\x00-\\x1f\\x7f]")

        /** Why Home Assistant would refuse to list [config], or null when it fits. */
        internal fun unfit(config: MicroWakeWordModelConfig): String? = when {
            config.wakeWord.length > MAX_PHRASE || CONTROL.containsMatchIn(config.wakeWord) ->
                "the wake word phrase must be at most $MAX_PHRASE plain characters"
            config.trainedLanguages.size > MAX_LANGUAGES ||
                config.trainedLanguages.any { it.length > MAX_LANGUAGE || CONTROL.containsMatchIn(it) } ->
                "the manifest lists too many trained languages, or one that is too long"
            else -> null
        }
        private const val JSON = ".json"
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        private const val MAX_MODEL_BYTES = 2 * 1024 * 1024
        private val ID = Regex("^[a-z][a-z0-9_]{0,63}$")

        /** The id an imported wake word gets from its name, or null when nothing usable remains. */
        fun idFor(name: String): String? {
            val reduced = name.substringBeforeLast('.').lowercase()
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(64)
            return reduced.takeIf(ID::matches)
        }

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
    }
}
