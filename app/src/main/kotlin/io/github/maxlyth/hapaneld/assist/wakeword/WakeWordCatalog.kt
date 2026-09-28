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
    fun available(): List<MicroWakeWordModelConfig> {
        val bundledIds = bundled.ids()
        val found = bundledIds.mapNotNull { id -> runCatching { MicroWakeWordModelConfig.parse(id, bundled.manifest(id)) }.getOrNull() }
        val imported = importDir.listFiles { file -> file.isDirectory && ID.matches(file.name) }.orEmpty()
            .map { it.name }.filter { it !in bundledIds }.sorted()
            .mapNotNull { id -> runCatching { importedConfig(id) }.getOrNull() }
        return found + imported
    }

    /** Load a model by id with the native engine; null when the id is unknown or the engine refuses it. */
    fun load(id: String): LoadedWakeWordModel? {
        if (!ID.matches(id)) return null
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

    private fun importedConfig(id: String): MicroWakeWordModelConfig =
        MicroWakeWordModelConfig.parse(id, File(File(importDir, id), "$id$JSON").readText())

    companion object {
        const val IMPORT_DIR = "wakeword"
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
