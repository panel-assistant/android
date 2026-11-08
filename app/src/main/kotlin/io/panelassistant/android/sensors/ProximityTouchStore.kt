package io.panelassistant.android.sensors

import android.content.Context

/**
 * Durable touch-learning state, one small JSON value per source key. Kept out of the calibration table,
 * which retains only its newest rows, so learning can never evict a model saved by setup. Not part of
 * backups: a restored panel relearns from use.
 */
internal interface ProximityTouchStore {
    fun read(key: String): String?
    fun write(key: String, json: String)

    companion object {
        val NONE = object : ProximityTouchStore {
            override fun read(key: String): String? = null
            override fun write(key: String, json: String) = Unit
        }

        fun preferences(context: Context): ProximityTouchStore {
            val prefs = context.getSharedPreferences("proximity_touch_learning", Context.MODE_PRIVATE)
            return object : ProximityTouchStore {
                override fun read(key: String): String? = prefs.getString(key, null)
                override fun write(key: String, json: String) { prefs.edit().putString(key, json).apply() }
            }
        }
    }
}
