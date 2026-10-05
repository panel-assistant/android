package io.panelassistant.android.util

import android.content.Context
import io.panelassistant.android.Config
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import java.io.File
import java.util.LinkedHashMap

/** Durable, private config snapshot taken immediately before replacing the running APK. */
internal object ConfigUpgradeBackup {
    private const val MAX_REVISIONS = 20

    fun snapshot(context: Context): Boolean = runCatching {
        val config = Config(context)
        val values = LinkedHashMap<String, String>()
        SettingsRegistry.settable().forEach { spec -> values[spec.key] = config.getRaw(spec) }
        SettingsRegistry.persistedExposureDefaults().forEach { (key, default) ->
            values[key] = config.haExposed(key.removePrefix(SettingsRegistry.HA_EXPOSE_PREFIX), default).toString()
        }
        val directory = File(context.filesDir, "config-revisions")
        check(directory.isDirectory || directory.mkdirs())
        val existing = directory.listFiles { file -> file.isFile && file.name.removeSuffix(".json").toLongOrNull() != null }
            ?.mapNotNull { it.name.removeSuffix(".json").toLongOrNull() }
            ?.sorted()
            .orEmpty()
        val id = maxOf(System.currentTimeMillis(), (existing.lastOrNull() ?: 0L) + 1L)
        val target = File(directory, "$id.json")
        val temporary = File.createTempFile(".$id.", ".partial", directory)
        try {
            temporary.writeText(
                ConfigBundle.fromValues(
                    values,
                    kind = ConfigBundle.KIND_REVISION,
                    exportedAt = id.toString(),
                    exportedBy = config.panelId,
                ).serialize(),
            )
            check(temporary.renameTo(target))
        } finally {
            temporary.delete()
        }
        existing.take((existing.size + 1 - MAX_REVISIONS).coerceAtLeast(0)).forEach { old ->
            File(directory, "$old.json").delete()
        }
        true
    }.getOrDefault(false)
}
