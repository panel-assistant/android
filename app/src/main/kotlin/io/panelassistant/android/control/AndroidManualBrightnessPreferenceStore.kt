package io.panelassistant.android.control

import android.content.Context
import android.provider.Settings
import io.panelassistant.android.persistence.AppState
import org.json.JSONObject

internal class AndroidManualBrightnessPreferenceStore(context: Context) : ManualBrightnessPreferenceStore {
    private val preferences = AppState.preferences(
        context,
        namespace = "auto-brightness-runtime",
        legacyName = "ha-paneld-auto-brightness-runtime",
    )

    override fun load(): ManualBrightnessPreferenceRecord? {
        val raw = preferences.getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ManualBrightnessPreferenceRecord(
                version = json.getInt("version"),
                requestedLevel = json.getInt("requested_level"),
                autoTargetAtStart = json.getInt("auto_target_at_start"),
                origin = BrightnessPreferenceOrigin.valueOf(json.getString("origin")),
                startedWallMs = json.getLong("started_wall_ms"),
                startedElapsedMs = json.getLong("started_elapsed_ms"),
                bootCount = json.optInt("boot_count", -1),
                modelContextKey = json.getString("model_context_key"),
                ambientSourceKey = json.getString("ambient_source_key"),
            )
        }.getOrNull()
    }

    override fun save(record: ManualBrightnessPreferenceRecord) {
        val json = JSONObject()
            .put("version", record.version)
            .put("requested_level", record.requestedLevel)
            .put("auto_target_at_start", record.autoTargetAtStart)
            .put("origin", record.origin.name)
            .put("started_wall_ms", record.startedWallMs)
            .put("started_elapsed_ms", record.startedElapsedMs)
            .put("boot_count", record.bootCount)
            .put("model_context_key", record.modelContextKey)
            .put("ambient_source_key", record.ambientSourceKey)
        preferences.edit().putString(KEY, json.toString()).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "manual_override_v1"

        fun bootCount(context: Context): Int = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)
    }
}
