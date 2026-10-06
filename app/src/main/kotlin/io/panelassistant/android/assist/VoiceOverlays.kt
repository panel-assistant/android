package io.panelassistant.android.assist

import android.app.Activity
import android.view.View
import android.view.ViewGroup

/**
 * The voice assistant's marks over the dashboard: the listening ripple and edge glow, and the microphone
 * mute chip. Each is a child of the decor view, so every content swap keeps it on top, and none takes a
 * touch. [show] attaches them once per window and connects [VoiceAttention] to them while the dashboard
 * is resumed; [hide] disconnects them when it pauses.
 */
internal object VoiceOverlays {
    private const val RIPPLE_TAG = "voice-ripple"
    private const val GLOW_TAG = "voice-glow"

    fun show(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val ripple = decor.findViewWithTag<WakeRippleView>(RIPPLE_TAG)
            ?: WakeRippleView(activity).also {
                it.tag = RIPPLE_TAG
                decor.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        val glow = decor.findViewWithTag<ListeningGlowView>(GLOW_TAG)
            ?: ListeningGlowView(activity).also {
                it.tag = GLOW_TAG
                it.visibility = View.GONE
                decor.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        // The chip's view carries its controller as its tag.
        val mute = (0 until decor.childCount).firstNotNullOfOrNull { decor.getChildAt(it).tag as? MicrophoneMutedChip }
            ?: MicrophoneMutedChip.attach(activity, decor)
        VoiceAttention.ripple = {
            activity.runOnUiThread {
                ripple.bringToFront()
                ripple.setColor(VoiceAttention.color)
                ripple.startRipple()
            }
        }
        VoiceAttention.listening = { active ->
            activity.runOnUiThread {
                glow.bringToFront()
                glow.setColor(VoiceAttention.color)
                glow.setListening(active)
            }
        }
        VoiceAttention.muteShown = { muted, announce -> activity.runOnUiThread { mute.show(muted, announce) } }
        glow.setColor(VoiceAttention.color)
        glow.setListening(VoiceAttention.attending)
        mute.show(VoiceAttention.muted, announce = false)
    }

    fun hide() {
        VoiceAttention.ripple = null
        VoiceAttention.listening = null
        VoiceAttention.muteShown = null
    }
}
