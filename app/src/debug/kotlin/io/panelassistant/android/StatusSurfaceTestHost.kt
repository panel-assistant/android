package io.panelassistant.android

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.FrameLayout

/**
 * An empty Activity that exists only so instrumented tests can measure [StatusSurface] on a real
 * runtime without launching a screen that has its own wiring and side effects.
 *
 * It lives in the DEBUG variant rather than in the test APK because instrumentation runs inside the
 * app process, and Android refuses to launch an Activity that belongs to the separate test package.
 * It is never part of a release build.
 */
class StatusSurfaceTestHost : Activity() {

    companion object {
        /**
         * Font scale the next launched host applies to its own base context, or `0f` for the device's.
         *
         * A static rather than an Intent extra because the override has to be in place in
         * [attachBaseContext], which runs before the Activity has an Intent. Set it, launch, and reset
         * it in a `finally`; nothing else reads it and it is debug-only.
         */
        @JvmStatic
        var fontScaleOverride: Float = 0f
    }

    override fun attachBaseContext(newBase: Context) {
        val scale = fontScaleOverride
        if (scale <= 0f) {
            super.attachBaseContext(newBase)
            return
        }
        // A real configuration override, so every sp dimension the frame resolves is genuinely scaled —
        // not a multiplier applied to measurements after the fact, which would prove nothing about how
        // Android actually lays the frame out.
        val scaled = Configuration(newBase.resources.configuration).apply { fontScale = scale }
        super.attachBaseContext(newBase.createConfigurationContext(scaled))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
