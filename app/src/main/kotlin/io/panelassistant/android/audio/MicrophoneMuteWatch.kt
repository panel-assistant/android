package io.panelassistant.android.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Follows Android's microphone mute: reads it once at [start], then again on each change Android
 * announces, and calls [onChange] when it differs. Before Android 9 there is no announcement, and the
 * read reaches the audio HAL, which on the Sonoff panels does not implement the mute query and logs a
 * warning per read (2026-10-05), so it is never polled; those panels have no mute control to follow.
 * Only an [offered] microphone is ever reported muted.
 */
internal class MicrophoneMuteWatch(
    private val context: Context,
    offered: () -> Boolean,
    private val onChange: (muted: Boolean) -> Unit,
) : AutoCloseable {
    val mute = MicrophoneMute { offered() && context.getSystemService(AudioManager::class.java)?.isMicrophoneMute == true }
    private var receiver: BroadcastReceiver? = null

    @Volatile private var closed = false

    private fun follow() {
        if (!closed && mute.refresh()) onChange(mute.muted)
    }

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (closed || receiver != null) return
        scope.launch { follow() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scope.launch { follow() }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(listener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(listener, filter)
            }
        }.onSuccess { receiver = listener }
            .onFailure { Log.w("ha-paneld/voice", "microphone mute changes could not be followed", it) }
    }

    /** Unregister; nothing is reported afterwards, and a later [start] does nothing. */
    @Synchronized
    override fun close() {
        closed = true
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }
}
