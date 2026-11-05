package io.panelassistant.android

import android.util.Log

/** Where [PanelCommandDispatcher] writes a command's outcome line. */
internal interface CommandLog {
    fun info(message: String)
    fun warn(message: String, error: Throwable)

    object Android : CommandLog {
        private const val TAG = "ha-paneld/commands"
        override fun info(message: String) { Log.i(TAG, message) }
        override fun warn(message: String, error: Throwable) { Log.w(TAG, message, error) }
    }
}
